package com.faforever.server.game;

import com.faforever.server.client.ClientService;
import com.faforever.server.client.ConnectionAware;
import com.faforever.server.client.GameResponses;
import com.faforever.server.config.ServerProperties;
import com.faforever.server.entity.ArmyOutcome;
import com.faforever.server.entity.ArmyScore;
import com.faforever.server.entity.Game;
import com.faforever.server.entity.GamePlayerStats;
import com.faforever.server.entity.GameState;
import com.faforever.server.entity.GlobalRating;
import com.faforever.server.entity.Ladder1v1Rating;
import com.faforever.server.entity.ModVersion;
import com.faforever.server.entity.Player;
import com.faforever.server.entity.Validity;
import com.faforever.server.entity.VictoryCondition;
import com.faforever.server.error.ErrorCode;
import com.faforever.server.error.ProgrammingError;
import com.faforever.server.error.RequestException;
import com.faforever.server.error.Requests;
import com.faforever.server.game.GameResponse.SimMod;
import com.faforever.server.map.MapService;
import com.faforever.server.mod.ModService;
import com.faforever.server.player.PlayerOnlineEvent;
import com.faforever.server.player.PlayerService;
import com.faforever.server.rating.RatingService;
import com.faforever.server.rating.RatingType;
import com.faforever.server.stats.ArmyStatistics;
import com.faforever.server.stats.ArmyStatisticsService;
import com.faforever.server.stats.Metrics;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Streams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import javax.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
// TODO make the game report the game time and use this instead of the real time
public class GameService {

  /**
   * ID of the team that stands for "no team" according to the game.
   */
  public static final int NO_TEAM_ID = 1;
  public static final int OBSERVERS_TEAM_ID = -1;
  public static final String OPTION_FOG_OF_WAR = "FogOfWar";
  public static final String OPTION_CHEATS_ENABLED = "CheatsEnabled";
  public static final String OPTION_PREBUILT_UNITS = "PrebuiltUnits";
  public static final String OPTION_NO_RUSH = "NoRushOption";
  public static final String OPTION_RESTRICTED_CATEGORIES = "RestrictedCategories";
  public static final String OPTION_SLOT = "Slot";
  public static final String OPTION_SLOTS = "Slots";
  public static final String OPTION_SCENARIO_FILE = "ScenarioFile";
  public static final String OPTION_TITLE = "Title";
  public static final String OPTION_TEAM = "Team";
  public static final String OPTION_VICTORY_CONDITION = VictoryCondition.GAME_OPTION_NAME;
  public static final Duration DEFAULT_MIN_DELAY = Duration.ofSeconds(1);
  public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(5);
  public static final String OPTION_START_SPOT = "StartSpot";
  public static final String OPTION_FACTION = "Faction";
  public static final String OPTION_COLOR = "Color";
  public static final String OPTION_ARMY = "Army";

  private final Collection<Function<Game, Validity>> validityVoters;
  private final GameRepository gameRepository;
  private final CounterService counterService;
  /**
   * Due to "performance reasons" the ID is generated by the server instead of the DB. See {@link #entityManager} for
   * implications caused by this.
   */
  private final AtomicInteger lastGameId;
  private final ClientService clientService;
  private final ConcurrentMap<Integer, Game> activeGamesById;
  private final MapService mapService;
  private final ModService modService;
  private final PlayerService playerService;
  private final RatingService ratingService;

  /**
   * Since Spring Data JPA assumes that entities with IDs != 0 (or != null) are already stored in the database, we can't
   * use {@link org.springframework.data.jpa.repository.support.SimpleJpaRepository#save(Object)} to store new games.
   * Instead, we have to use the entity manager directly which gives us more control over whether to insert or update an
   * entity.
   */
  private final EntityManager entityManager;
  private ArmyStatisticsService armyStatisticsService;

  public GameService(GameRepository gameRepository, CounterService counterService, ClientService clientService,
                     MapService mapService, ModService modService, PlayerService playerService,
                     RatingService ratingService, ServerProperties properties, EntityManager entityManager,
                     ArmyStatisticsService armyStatisticsService) {
    this.gameRepository = gameRepository;
    this.counterService = counterService;
    this.clientService = clientService;
    this.mapService = mapService;
    this.modService = modService;
    this.playerService = playerService;
    this.ratingService = ratingService;
    this.entityManager = entityManager;
    this.armyStatisticsService = armyStatisticsService;
    lastGameId = new AtomicInteger(0);
    activeGamesById = new ConcurrentHashMap<>();
    Stream.of(GameState.values()).forEach(state -> counterService.reset(String.format(Metrics.GAMES_STATE_FORMAT, state)));

    validityVoters = Arrays.asList(
      ValidityVoter.isRankedVoter(modService),
      ValidityVoter.victoryConditionVoter(modService),
      ValidityVoter.freeForAllVoter(),
      ValidityVoter.evenTeamsVoter(),
      ValidityVoter.fogOfWarVoter(),
      ValidityVoter.cheatsEnabledVoter(),
      ValidityVoter.prebuiltUnitsVoter(),
      ValidityVoter.noRushVoter(),
      ValidityVoter.restrictedUnitsVoter(),
      ValidityVoter.rankedMapVoter(),
      ValidityVoter.desyncVoter(),
      ValidityVoter.mutualDrawVoter(),
      ValidityVoter.singlePlayerVoter(),
      ValidityVoter.gameResultVoter(),
      ValidityVoter.gameLengthVoter(properties)
    );
  }

  @EventListener
  @Transactional(readOnly = true)
  public void onApplicationEvent(ContextRefreshedEvent event) {
    gameRepository.findMaxId().ifPresent(lastGameId::set);
    log.debug("Next game ID is: {}", lastGameId.get() + 1);
  }

  /**
   * Creates a new, transient game with the specified options and tells the client to start the game process. The
   * player's current game is set to the new game.
   *
   * @return a future that will be completed as soon as the player's game has been started and is ready to be joined. Be
   * aware that there are various reasons for the game to never start (crash, disconnect, abort) so never wait without a
   * timeout.
   */
  @Transactional(readOnly = true)
  public CompletableFuture<Game> createGame(String title, String featuredModName, String mapname,
                                            String password, GameVisibility visibility,
                                            Integer minRating, Integer maxRating, Player player) {

    Game currentGame = player.getCurrentGame();
    if (currentGame != null && currentGame.getState() == GameState.INITIALIZING) {
      /* Apparently, the user's game crashed before it reached the lobby, leaving it in state INITIALIZING. One solution
      is to let the game time out, but as usual, that's not a good solution since no matter what timeout one chooses,
      there is always some drawback. Therefore, we don't timeout games but reset a player's game state when he tries
      to create a new game. Or when he logs out. */
      removePlayer(currentGame, player);
    }

    Requests.verify(currentGame == null, ErrorCode.ALREADY_IN_GAME);

    int gameId = this.lastGameId.incrementAndGet();
    Game game = new Game(gameId);
    game.setHost(player);
    modService.getFeaturedMod(featuredModName).ifPresent(game::setFeaturedMod);
    game.setTitle(title);
    mapService.findMap(mapname).ifPresent(game::setMap);
    game.setMapName(mapname);
    game.setPassword(password);
    game.setGameVisibility(visibility);
    game.setMinRating(minRating);
    game.setMaxRating(maxRating);

    activeGamesById.put(game.getId(), game);
    counterService.increment(String.format(Metrics.GAMES_STATE_FORMAT, game.getState()));

    log.debug("Player '{}' created game '{}'", player, game);

    clientService.startGameProcess(game, player);
    player.setGameBeingJoined(game);

    return game.getJoinableFuture();
  }

  /**
   * Tells the client to start the game process and sets the player's current game to it.
   */
  public void joinGame(int gameId, Player player) {
    Requests.verify(player.getCurrentGame() == null, ErrorCode.ALREADY_IN_GAME);

    Game game = getActiveGame(gameId).orElseThrow(() -> new IllegalArgumentException("No such game: " + gameId));
    Requests.verify(game.getState() == GameState.OPEN, ErrorCode.GAME_NOT_JOINABLE);

    log.debug("Player '{}' joins game '{}'", player, gameId);
    clientService.startGameProcess(game, player);
    player.setGameBeingJoined(game);
  }

  /**
   * Updates the game state of a player's game.
   */
  @Transactional
  public void updatePlayerGameState(PlayerGameState newState, Player player) {
    Game gameBeingJoined = player.getGameBeingJoined();
    Game currentGame = player.getCurrentGame();
    Game game = null;
    if (gameBeingJoined != null) {
      game = gameBeingJoined;
    } else if (currentGame != null) {
      game = currentGame;
    }
    Requests.verify(game != null, ErrorCode.NOT_IN_A_GAME);

    PlayerGameState oldState = player.getGameState();
    log.debug("Player '{}' updated his game state from '{}' to '{}' (game: '{}')", player, oldState, newState, game);

    Requests.verify(PlayerGameState.canTransition(oldState, newState), ErrorCode.INVALID_PLAYER_GAME_STATE_TRANSITION, oldState, newState);

    changePlayerGameState(player, newState);
    switch (newState) {
      case LOBBY:
        onLobbyEntered(player, game);
        break;
      case LAUNCHING:
        onGameLaunching(player, game);
        break;
      case ENDED:
        onPlayerGameEnded(player, game);
        break;
      case IDLE:
        log.warn("Ignoring state '{}' from player '{}' for game '{}' (should be handled by the client)", newState, player, game);
        break;
      default:
        throw new ProgrammingError("Uncovered state: " + newState);
    }
  }

  /**
   * Returns the active game with the specified ID, if such a game exists. A game is considered active as soon as it's
   * being hosted and until it finished.
   */
  public Optional<Game> getActiveGame(int id) {
    return Optional.ofNullable(activeGamesById.get(id));
  }

  /**
   * Updates an option value of the game that is currently being hosted by the specified host. If the specified player
   * is not currently hosting a game, this method does nothing.
   */
  public void updateGameOption(Player reporter, String key, Object value) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      // Since this is called repeatedly, throwing exceptions here would not be a good idea
      log.debug("Received game option for player w/o game: {}", reporter);
      return;
    }
    Requests.verify(Objects.equals(reporter.getCurrentGame().getHost(), reporter), ErrorCode.HOST_ONLY_OPTION, key);

    log.trace("Updating option for game '{}': '{}' = '{}'", game, key, value);
    game.getOptions().put(key, value);
    if (VictoryCondition.GAME_OPTION_NAME.equals(key)) {
      game.setVictoryCondition(VictoryCondition.fromString((String) value));
    } else if (OPTION_SLOTS.equals(key)) {
      game.setMaxPlayers((int) value);
    } else if (OPTION_SCENARIO_FILE.equals(value)) {
      game.setMapName(((String) value).replace("//", "/").replace("\\", "/").split("/")[2]);
    } else if (OPTION_TITLE.equals(value)) {
      game.setTitle((String) value);
    }
    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
  }

  /**
   * Updates an option value of a specific player. Only the host of a game is allowed to report such options and for
   * unstarted games, otherwise an exception will be thrown.
   *
   * @throws RequestException if the reporting player is not the host, or if the game is not in state {@link
   * GameState#OPEN}
   */
  public void updatePlayerOption(Player reporter, int playerId, String key, Object value) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      // Since this is called repeatedly, throwing exceptions here would not be a good idea. Happens after restarts.
      log.warn("Received player option for player w/o game: {}", reporter);
      return;
    }
    Requests.verify(game.getState() == GameState.OPEN, ErrorCode.INVALID_GAME_STATE, game.getState(), GameState.OPEN);
    Requests.verify(Objects.equals(reporter.getCurrentGame().getHost(), reporter), ErrorCode.HOST_ONLY_OPTION, key);

    if (!game.getConnectedPlayers().containsKey(playerId)) {
      log.warn("Player '{}' reported option '{}' with value '{}' for unknown player '{}' in game '{}'", reporter, key, value, playerId, game);
      return;
    }

    log.trace("Updating option for player '{}' in game '{}': '{}' = '{}'", playerId, game.getId(), key, value);
    game.getPlayerOptions().computeIfAbsent(playerId, id -> new HashMap<>()).put(key, value);

    markDirty(game, Duration.ofSeconds(1), Duration.ofSeconds(5));
  }

  /**
   * Updates an option value of a specific AI player. Only the host of a game is allowed to report such options,
   * otherwise an exception will be thrown.
   *
   * @throws RequestException if the reporting player is not the host
   */
  public void updateAiOption(Player reporter, String aiName, String key, Object value) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      // Since this is called repeatedly, throwing exceptions here would not be a good idea. Happens after restarts.
      log.warn("Received AI option for player w/o game: {}", reporter);
      return;
    }
    Requests.verify(Objects.equals(reporter.getCurrentGame().getHost(), reporter), ErrorCode.HOST_ONLY_OPTION, key);

    log.trace("Updating option for AI '{}' in game '{}': '{}' = '{}'", aiName, game.getId(), key, value);
    game.getAiOptions().computeIfAbsent(aiName, s -> new HashMap<>()).put(key, value);
    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
  }

  /**
   * Removes all player or AI options that are associated with the specified slot.
   */
  public void clearSlot(Game game, int slotId) {
    if (game == null) {
      log.warn("Clearing slot '{}' was requested for a null game", slotId);
      return;
    }
    log.trace("Clearing slot '{}' of game '{}'", slotId, game);

    game.getPlayerOptions().entrySet().stream()
      .filter(entry -> Objects.equals(entry.getValue().get(OPTION_SLOT), slotId))
      .map(Entry::getKey)
      .collect(Collectors.toList())
      .forEach(playerId -> {
        log.trace("Removing options for player '{}' in game '{}'", playerId, game);
        game.getPlayerOptions().remove(playerId);
      });

    game.getAiOptions().entrySet().stream()
      .filter(entry -> Objects.equals(entry.getValue().get(OPTION_SLOT), slotId))
      .map(Entry::getKey)
      .collect(Collectors.toList())
      .forEach(aiName -> {
        log.trace("Removing options for AI '{}' in game '{}'", aiName, game);
        game.getAiOptions().remove(aiName);
      });

    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
  }

  /**
   * Increments the desync counter for a player's game. If the specified player is currently not in a game, this method
   * does nothing.
   */
  public void reportDesync(Player reporter) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      log.warn("Desync reported by player w/o game: {}", reporter);
      return;
    }

    int desyncCount = game.getDesyncCounter().incrementAndGet();
    log.debug("Player '{}' increased desync count to '{}' for game: {}", reporter, desyncCount, game);
  }

  /**
   * Updates the list of activated mod UIDs. Not all UIDs may be known to the server. This is the list of mods user see
   * in the client.
   */
  public void updateGameMods(Game game, List<String> modUids) {
    List<ModVersion> modVersions = modService.findModVersionsByUids(modUids);

    game.getSimMods().clear();
    game.getSimMods().addAll(modVersions);

    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
  }

  public void updateGameModsCount(Game game, int count) {
    if (count != 0) {
      return;
    }
    log.trace("Clearing mod list for game '{}'", game);
    game.getSimMods().clear();
    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
  }

  public void reportArmyScore(Player reporter, int armyId, int score) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      log.warn("Army result reported by player w/o game: {}", reporter);
      return;
    }

    if (!hasArmy(game, armyId)) {
      log.warn("Player '{}' reported score '{}' for unknown army '{}' in game '{}'", reporter, score, armyId, game);
      return;
    }

    log.debug("Player '{}' reported score '{}' for army '{}' in game '{}'", reporter, score, armyId, game);
    game.getReportedArmyScores().computeIfAbsent(reporter.getId(), playerId -> new ArrayList<>()).add(new ArmyScore(armyId, score));
  }

  public void reportArmyOutcome(Player reporter, int armyId, Outcome outcome) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      log.warn("Army score reported by player w/o game: {}", reporter);
      return;
    }

    if (!hasArmy(game, armyId)) {
      log.warn("Player '{}' reported outcome '{}' for unknown army '{}' in game '{}'", reporter, outcome, armyId, game);
      return;
    }

    log.debug("Player '{}' reported result for army '{}' in game '{}': {}", reporter, armyId, game, outcome);
    game.getReportedArmyOutcomes().computeIfAbsent(reporter.getId(), playerId -> new ArrayList<>()).add(new ArmyOutcome(armyId, outcome));

    endGameIfArmyOutcomesComplete(game);
  }

  /**
   * Updates the game's army statistics. Last reporter wins.
   */
  public void reportArmyStatistics(Player reporter, List<ArmyStatistics> armyStatistics) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      log.warn("Game statistics reported by player w/o game: {}", reporter);
      return;
    }
    game.replaceArmyStatistics(armyStatistics);
  }

  /**
   * Enforce rating even though the minimum game time has not yet been reached.
   */
  public void enforceRating(Player reporter) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      log.warn("Game statistics reported by player w/o game: {}", reporter);
      return;
    }

    log.debug("Player '{}' enforced rating for game '{}'", reporter, game);
    game.setRatingEnforced(true);
  }

  @EventListener
  public void onPlayerOnlineEvent(PlayerOnlineEvent event) {
    clientService.sendGameList(
      new GameResponses(activeGamesById.values().stream().map(this::toResponse).collect(Collectors.toList())),
      event.getPlayer()
    );
  }

  @Transactional
  public void removePlayer(Player player) {
    Optional.ofNullable(player.getCurrentGame()).ifPresent(game -> removePlayer(game, player));
    Optional.ofNullable(player.getGameBeingJoined()).ifPresent(game -> removePlayer(game, player));
  }

  /**
   * Tells all peers of the player with the specified ID to drop their connections to him/her.
   */
  public void disconnectPlayerFromGame(Player requester, int playerId) {
    Optional<Player> optional = playerService.getOnlinePlayer(playerId);
    if (!optional.isPresent()) {
      log.warn("User '{}' tried to disconnect unknown player '{}' from game", requester, playerId);
      return;
    }
    Player player = optional.get();
    Game game = player.getCurrentGame();
    if (game == null) {
      log.warn("User '{}' tried to disconnect player '{}' from game, but no game is associated", requester, player);
      return;
    }

    Collection<? extends ConnectionAware> receivers = game.getConnectedPlayers().values().stream()
      .filter(item -> !Objects.equals(item.getId(), playerId))
      .collect(Collectors.toList());

    clientService.disconnectPlayerFromGame(playerId, receivers);
    log.info("User '{}' disconnected player '{}' from game '{}'", requester, player, game);
  }

  /**
   * Associates the specified player with the specified game, if this player was previously part of the game and the
   * game is still running. This is requested by the client after it lost connection to the server.
   */
  public void restoreGameSession(Player player, int gameId) {
    if (player.getCurrentGame() != null) {
      log.warn("Player '{}' requested game session restoration but is still associated with game '{}'", player, player.getCurrentGame());
      return;
    }

    Optional<Game> gameOptional = getActiveGame(gameId);
    Requests.verify(gameOptional.isPresent(), ErrorCode.CANT_RESTORE_GAME_DOESNT_EXIST);

    Game game = gameOptional.get();
    GameState gameState = game.getState();
    Requests.verify(gameState == GameState.OPEN || gameState == GameState.PLAYING, ErrorCode.CANT_RESTORE_GAME_DOESNT_EXIST);
    Requests.verify(game.getState() != GameState.PLAYING
      || game.getPlayerStats().containsKey(player.getId()), ErrorCode.CANT_RESTORE_GAME_NOT_PARTICIPANT);

    log.debug("Reassociating player '{}' with game '{}'", player, game);
    addPlayer(game, player);
  }

  public void mutuallyAgreeDraw(Player player) {
    Requests.verify(player.getCurrentGame() != null, ErrorCode.NOT_IN_A_GAME);

    Game game = player.getCurrentGame();

    GameState gameState = game.getState();
    Requests.verify(gameState == GameState.PLAYING, ErrorCode.INVALID_GAME_STATE, GameState.PLAYING);

    getPlayerTeamId(player)
      .filter(teamId -> OBSERVERS_TEAM_ID != teamId)
      .ifPresent(teamId -> {
        log.debug("Adding player '{}' to mutually accepted draw list in game '{}'", player, game);
        game.getMutuallyAcceptedDrawPlayerIds().add(player.getId());

        final boolean allConnectedNonObserverPlayersAgreedOnMutualDraw = game.getConnectedPlayers().values().stream()
          .filter(connectedPlayer -> !getPlayerTeamId(connectedPlayer).equals(Optional.of(OBSERVERS_TEAM_ID))
            && !getPlayerTeamId(connectedPlayer).equals(Optional.empty()))
          .allMatch(connectedPlayer -> game.getMutuallyAcceptedDrawPlayerIds().contains(connectedPlayer.getId()));

        if (allConnectedNonObserverPlayersAgreedOnMutualDraw) {
          log.debug("All in-game players agreed on mutual draw. Setting mutually agreed draw state in game '{}'", game);
          game.setMutuallyAgreedDraw(true);
        }
      });
  }

  private void changePlayerGameState(Player player, PlayerGameState newState) {
    counterService.decrement(String.format(Metrics.PLAYER_GAMES_STATE_FORMAT, player.getGameState()));
    player.setGameState(newState);
    counterService.increment(String.format(Metrics.PLAYER_GAMES_STATE_FORMAT, newState));
  }

  /**
   * Checks whether every connected player reported outcomes for all armies (human and AI). If so, the game is set to
   * {@link GameState#CLOSED}.
   */
  private void endGameIfArmyOutcomesComplete(Game game) {
    List<Integer> armies = Streams.concat(
      game.getPlayerOptions().values().stream(),
      game.getAiOptions().values().stream()
    ).map(options -> (Integer) options.get(OPTION_ARMY))
      .collect(Collectors.toList());

    Map<Integer, Player> connectedPlayers = game.getConnectedPlayers();
    Map<Integer, List<ArmyOutcome>> reportedArmyOutcomes = game.getReportedArmyOutcomes();

    for (Integer playerId : connectedPlayers.keySet()) {
      List<ArmyOutcome> reportedOutcomes = reportedArmyOutcomes.get(playerId);
      if (reportedOutcomes == null) {
        return;
      }
      Collection<Integer> armiesWithoutOutcomeReport = new ArrayList<>(armies);
      for (ArmyOutcome outcome : reportedOutcomes) {
        armiesWithoutOutcomeReport.remove(outcome.getArmyId());
      }
      if (!armiesWithoutOutcomeReport.isEmpty()) {
        if (log.isTraceEnabled()) {
          log.trace("Not considering game as completed because player '{}' did not report scores for armies: {}",
            playerId, Joiner.on(", ").join(armiesWithoutOutcomeReport));
        }
        return;
      }
    }
    onGameEnded(game);
  }

  private void addPlayer(Game game, Player player) {
    game.getConnectedPlayers().put(player.getId(), player);
    player.setCurrentGame(game);
    player.setGameBeingJoined(null);

    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
  }

  private void removePlayer(Game game, Player player) {
    log.debug("Removing player '{}' from game '{}'", player, game);

    int playerId = player.getId();
    changePlayerGameState(player, PlayerGameState.NONE);
    player.setCurrentGame(null);
    player.setGameBeingJoined(null);
    game.getConnectedPlayers().remove(playerId, player);
    // Discard reports of disconnected players since their report may not reflect the end result
    game.getReportedArmyScores().remove(playerId);

    if (game.getConnectedPlayers().isEmpty()) {
      if (game.getState() == GameState.INITIALIZING) {
        onGameCancelled(game);
      } else if (game.getState() != GameState.CLOSED) {
        onGameEnded(game);
      }
    }
  }

  private void onPlayerGameEnded(Player reporter, Game game) {
    if (game == null) {
      // Since this is called repeatedly, throwing exceptions here would not be a good idea. Happens after restarts.
      log.warn("Received player option for player w/o game: {}", reporter);
      return;
    }

    log.debug("Player '{}' left game: {}", reporter, game);
    removePlayer(game, reporter);
  }

  private void onGameCancelled(Game game) {
    log.debug("Game cancelled: {}", game);
    removeGame(game);
  }

  private void onGameEnded(Game game) {
    log.debug("Game ended: {}", game);

    try {
      // Games can also end before they even started.
      if (game.getState() == GameState.PLAYING) {
        game.getPlayerStats().values().forEach(stats -> {
          Player player = stats.getPlayer();
          armyStatisticsService.process(player, game, game.getArmyStatistics());
        });
        game.setEndTime(Instant.now());
        updateGameValidity(game);
        updateRatingsIfValid(game);
        updateScores(game);
        gameRepository.save(game);
      }
    } finally {
      removeGame(game);
    }
  }

  private void removeGame(Game game) {
    if (activeGamesById.remove(game.getId()) != null) {
      changeGameState(game, GameState.CLOSED);
      counterService.decrement(String.format(Metrics.GAMES_STATE_FORMAT, game.getState()));
    }
    markDirty(game, Duration.ZERO, Duration.ZERO);
  }

  /**
   * Sets the score of each player according to the reported score of his army.
   */
  private void updateScores(Game game) {
    Map<Integer, ArmyScore> armyIdsToMostReportedScore = findMostReportedArmyScores(game);

    for (Entry<Integer, GamePlayerStats> entry : game.getPlayerStats().entrySet()) {
      Integer playerId = entry.getKey();
      Integer armyScore = Optional.ofNullable(game.getPlayerOptions())
        .map(playerOptions -> playerOptions.get(playerId))
        .map(options -> (Integer) options.get(OPTION_ARMY))
        .map(armyIdsToMostReportedScore::get)
        .map(ArmyScore::getScore)
        .orElse(null);

      entry.getValue().setScore(armyScore).setScoreTime(Instant.now());
    }
  }

  /**
   * Finds the {@link ArmyScore ArmyScores} that have been reported the most, per army ID.
   */
  private Map<Integer, ArmyScore> findMostReportedArmyScores(Game game) {
    Map<ArmyScore, Long> armyScoreToOccurrence = game.getReportedArmyScores().values().stream()
      .flatMap(Collection::stream)
      .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

    Map<Integer, ArmyScore> armyIdsToMostReportedScore = new HashMap<>();
    Map<Integer, Long> mostOccurrencesByArmyId = new HashMap<>();

    for (Entry<ArmyScore, Long> entry : armyScoreToOccurrence.entrySet()) {
      ArmyScore armyScore = entry.getKey();
      Long occurrence = entry.getValue();

      int armyId = armyScore.getArmyId();
      Long mostOccurrence = mostOccurrencesByArmyId.get(armyId);
      if (mostOccurrence == null || occurrence > mostOccurrence) {
        mostOccurrencesByArmyId.put(armyId, occurrence);
        armyIdsToMostReportedScore.put(armyId, armyScore);
      }
    }
    return armyIdsToMostReportedScore;
  }

  private void updateRatingsIfValid(Game game) {
    if (game.getValidity() != Validity.VALID && !game.isRatingEnforced()) {
      return;
    }
    RatingType ratingType = modService.isLadder1v1(game.getFeaturedMod()) ? RatingType.LADDER_1V1 : RatingType.GLOBAL;
    ratingService.updateRatings(game.getPlayerStats().values(), NO_TEAM_ID, ratingType);
  }

  private void onGameLaunching(Player reporter, Game game) {
    if (!Objects.equals(game.getHost(), reporter)) {
      // TODO do non-hosts send this? If not, log to WARN, else ignore if not host
      log.warn("Player '{}' reported launch for game: {}", reporter, game);
      return;
    }
    changeGameState(game, GameState.PLAYING);
    game.setStartTime(Instant.now());

    createGamePlayerStats(game);

    // Not using repository since ID is already set but entity is not yet persisted. There's no auto_increment.
    entityManager.persist(game);
    log.debug("Game launched: {}", game);
    markDirty(game, Duration.ZERO, Duration.ZERO);
  }

  private void changeGameState(Game game, GameState newState) {
    counterService.decrement(String.format(Metrics.GAMES_STATE_FORMAT, game.getState()));
    game.setState(newState);
    counterService.increment(String.format(Metrics.GAMES_STATE_FORMAT, newState));
  }

  private void createGamePlayerStats(Game game) {
    // TODO test how this handles observers
    game.getConnectedPlayers().values()
      .forEach(player -> {
        GamePlayerStats gamePlayerStats = new GamePlayerStats(game, player);

        Optional<Map<String, Object>> optional = Optional.ofNullable(game.getPlayerOptions().get(player.getId()));
        if (!optional.isPresent()) {
          log.warn("No player options available for player '{}' in game '{}'", player, game);
        } else {
          Map<String, Object> options = optional.get();
          updateGamePlayerStatsFromOptions(gamePlayerStats, options);
        }

        updateGamePlayerStatsRating(gamePlayerStats, player);
        gamePlayerStats.setPlayer(player);

        game.getPlayerStats().put(player.getId(), gamePlayerStats);
      });
  }

  private void updateGamePlayerStatsRating(GamePlayerStats gamePlayerStats, Player player) {
    Game game = player.getCurrentGame();

    if (modService.isLadder1v1(game.getFeaturedMod())) {
      if (player.getLadder1v1Rating() == null) {
        ratingService.initLadder1v1Rating(player);
      }
      Assert.state(Optional.ofNullable(player.getLadder1v1Rating()).isPresent(),
        "Ladder1v1 rating not properly initialized");

      Ladder1v1Rating ladder1v1Rating = player.getLadder1v1Rating();
      gamePlayerStats.setDeviation(ladder1v1Rating.getDeviation());
      gamePlayerStats.setMean(ladder1v1Rating.getMean());
    } else {
      if (player.getGlobalRating() == null) {
        ratingService.initGlobalRating(player);
      }
      Assert.state(Optional.ofNullable(player.getGlobalRating()).isPresent(),
        "Global rating not properly initialized");

      GlobalRating globalRating = player.getGlobalRating();
      gamePlayerStats.setDeviation(globalRating.getDeviation());
      gamePlayerStats.setMean(globalRating.getMean());
    }
  }

  private void updateGamePlayerStatsFromOptions(GamePlayerStats gamePlayerStats, Map<String, Object> options) {
    Arrays.asList(
      Pair.of(OPTION_TEAM, (Consumer<Integer>) gamePlayerStats::setTeam),
      Pair.of(OPTION_FACTION, (Consumer<Integer>) gamePlayerStats::setFaction),
      Pair.of(OPTION_COLOR, (Consumer<Integer>) gamePlayerStats::setColor),
      Pair.of(OPTION_START_SPOT, (Consumer<Integer>) gamePlayerStats::setStartSpot)
    ).forEach(pair -> {
      String key = pair.getFirst();

      Optional<Integer> value = Optional.ofNullable((Integer) options.get(key));
      if (value.isPresent()) {
        pair.getSecond().accept(value.get());
      } else {
        Player player = gamePlayerStats.getPlayer();
        log.warn("Missing option '{}' for player '{}' in game '{}'", key, player, player.getCurrentGame());
      }
    });
  }

  /**
   * <p>Called when a player's game entered {@link PlayerGameState#LOBBY}. If the player is host, the state of the
   * {@link Game} instance will be updated and the player is requested to "host" a game (open a port so others can
   * connect). A joining player whose game entered {@link PlayerGameState#LOBBY} will be told to connect to the host and
   * any other players in the game.</p> <p>In any case, the player will be added to the game's transient list of
   * participants where team information, faction and color will be set. When the game starts, this list will be reduced
   * to only the players who are in the game and then persisted.</p>
   */
  private void onLobbyEntered(Player player, Game game) {
    log.debug("Player '{}' entered state: {}", player, PlayerGameState.LOBBY);
    addPlayer(game, player);
    if (Objects.equals(game.getHost(), player)) {
      changeGameState(game, GameState.OPEN);
      clientService.hostGame(game, player);

      markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
    } else {
      clientService.connectToHost(game, player);
      game.getConnectedPlayers().values().forEach(otherPlayer -> connectPeers(player, otherPlayer));
    }
  }

  private void connectPeers(Player player, Player otherPlayer) {
    if (player.equals(otherPlayer)) {
      log.warn("Player '{}' should not be told to connect to himself", player);
      return;
    }
    clientService.connectToPlayer(player, player);
  }

  private boolean hasArmy(Game game, int armyId) {
    return Stream.concat(game.getPlayerOptions().values().stream(), game.getAiOptions().values().stream())
      .filter(options -> options.containsKey("Army"))
      .map(options -> (int) options.get("Army"))
      .anyMatch(id -> id == armyId);
  }

  private void markDirty(Game game, Duration minDelay, Duration maxDelay) {
    clientService.broadcastDelayed(toResponse(game), minDelay, maxDelay, gameResponse -> "game-" + gameResponse.getId());
  }

  private GameResponse toResponse(Game game) {
    return new GameResponse(
      game.getId(),
      game.getTitle(),
      game.getGameVisibility(),
      game.getPassword(),
      game.getState(),
      game.getFeaturedMod().getTechnicalName(),
      toSimMods(game.getSimMods()),
      game.getMapName(),
      game.getHost().getLogin(),
      toPlayers(game),
      game.getMaxPlayers(),
      Optional.ofNullable(game.getStartTime()).orElse(null),
      game.getMinRating(),
      game.getMaxRating()
    );
  }

  private List<SimMod> toSimMods(List<ModVersion> simMods) {
    return simMods.stream()
      .map(modVersion -> new SimMod(modVersion.getUid(), modVersion.getMod().getDisplayName()))
      .collect(Collectors.toList());
  }

  private List<GameResponse.Player> toPlayers(Game game) {
    return game.getConnectedPlayers().values().stream()
      .map(player -> {
        int team = (int) Optional.ofNullable(game.getPlayerOptions().get(player.getId()))
          .map(options -> options.get(OPTION_TEAM))
          .orElse(NO_TEAM_ID);

        return new GameResponse.Player(team, player.getLogin());
      })
      .collect(Collectors.toList());
  }

  private Optional<Integer> getPlayerTeamId(Player player) {
    Optional<Game> gameOptional = Optional.ofNullable(player.getCurrentGame());
    if (!gameOptional.isPresent()) {
      return Optional.empty();
    }
    Game game = gameOptional.get();
    return Optional.ofNullable((Integer) game.getPlayerOptions().get(player.getId()).get(OPTION_TEAM));
  }

  /**
   * Checks the game settings and determines whether the game is ranked. If the game is unranked, its "rankiness" will
   * be updated
   */
  @VisibleForTesting
  void updateGameValidity(Game game) {
    // You'd expect a null check on 'validity' here but since the DB field is not nullable the default value is RANKED.

    Assert.state(game.getValidity() == Validity.VALID, "Validity of game '" + game + "' has already been set to: " + game.getValidity());
    Assert.state(game.getState() == GameState.PLAYING, "Validity of game '" + game + "' can't be set while in state: " + game.getState());

    validityVoters.stream()
      .map(voter -> voter.apply(game))
      .filter(validity -> validity != Validity.VALID)
      .findFirst()
      .ifPresent(game::setValidity);
  }
}
