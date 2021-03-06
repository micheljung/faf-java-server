package com.faforever.server.game;

import com.faforever.server.client.ClientService;
import com.faforever.server.client.ConnectionAware;
import com.faforever.server.client.GameResponses;
import com.faforever.server.config.ServerProperties;
import com.faforever.server.error.ErrorCode;
import com.faforever.server.error.ProgrammingError;
import com.faforever.server.error.RequestException;
import com.faforever.server.error.Requests;
import com.faforever.server.game.GameResponse.FeaturedModFileVersion;
import com.faforever.server.game.GameResponse.SimMod;
import com.faforever.server.game.GameResultMessage.PlayerResult;
import com.faforever.server.ladder1v1.DivisionService;
import com.faforever.server.ladder1v1.Ladder1v1Rating;
import com.faforever.server.map.MapService;
import com.faforever.server.mod.FeaturedMod;
import com.faforever.server.mod.FeaturedModFile;
import com.faforever.server.mod.ModService;
import com.faforever.server.mod.ModVersion;
import com.faforever.server.player.Player;
import com.faforever.server.player.PlayerOfflineEvent;
import com.faforever.server.player.PlayerOnlineEvent;
import com.faforever.server.player.PlayerService;
import com.faforever.server.rating.GlobalRating;
import com.faforever.server.rating.RatingService;
import com.faforever.server.rating.RatingType;
import com.faforever.server.stats.ArmyStatistics;
import com.faforever.server.stats.ArmyStatisticsService;
import com.faforever.server.stats.Metrics;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.repository.CrudRepository;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
@Service
// TODO make the game report the game time and use this instead of the real time
public class GameService {

  /**
   * ID of the team that stands for "no team" according to the game.
   */
  public static final int NO_TEAM_ID = 1;
  public static final int OBSERVERS_TEAM_ID = -1;
  public static final int COOP_DIFFICULTY = 3;
  public static final int DEFAULT_EXPANSION = 1;
  public static final String OPTION_FOG_OF_WAR = "FogOfWar";
  public static final String OPTION_CHEATS_ENABLED = "CheatsEnabled";
  public static final String OPTION_PREBUILT_UNITS = "PrebuiltUnits";
  public static final String OPTION_NO_RUSH = "NoRushOption";
  public static final String OPTION_RESTRICTED_CATEGORIES = "RestrictedCategories";
  public static final String OPTION_SLOTS = "Slots";
  public static final String OPTION_SCENARIO_FILE = "ScenarioFile";
  public static final String OPTION_TITLE = "Title";
  public static final String OPTION_TEAM = "Team";
  public static final String OPTION_TEAM_LOCK = "TeamLock";
  public static final String OPTION_TEAM_SPAWN = "TeamSpawn";
  public static final String OPTION_CIVILIANS_REVEALED = "RevealedCivilians";
  public static final String OPTION_DIFFICULTY = "Difficulty";
  public static final String OPTION_EXPANSION = "Expansion";
  public static final String OPTION_VICTORY_CONDITION = VictoryCondition.GAME_OPTION_NAME;
  public static final Duration DEFAULT_MIN_DELAY = Duration.ofSeconds(1);
  public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(5);
  public static final String OPTION_START_SPOT = "StartSpot";
  public static final String OPTION_FACTION = "Faction";
  public static final String OPTION_COLOR = "Color";
  public static final String OPTION_ARMY = "Army";

  private static final BiFunction<GameResponse, GameResponse, GameResponse> GAME_RESPONSE_AGGREGATOR = (oldObject, newObject) -> newObject;

  @VisibleForTesting
  static final String TAG_GAME_STATE = "state";
  @VisibleForTesting
  private static final String TAG_PLAYER_GAME_STATE = PlayerService.TAG_PLAYER_GAME_STATE;

  private final Collection<Function<Game, Validity>> validityVoters;
  private final GameRepository gameRepository;

  /**
   * Due to "performance reasons" the ID is generated by the server instead of the DB. See {@link #entityManager} for
   * implications caused by this.
   */
  private final AtomicInteger lastGameId;
  private final ClientService clientService;
  private final MapService mapService;
  private final ModService modService;
  private final PlayerService playerService;
  private final RatingService ratingService;
  private final DivisionService divisionService;
  private final Map<GameState, AtomicInteger> gameStateCounters;
  private final Map<PlayerGameState, AtomicInteger> playerGameStateCounters;
  private final ActiveGameRepository activeGameRepository;

  /**
   * Since Spring Data JPA assumes that entities with IDs != 0 (or != null) are already stored in the database, we can't
   * use {@link org.springframework.data.jpa.repository.support.SimpleJpaRepository#save(Object)} to store new games.
   * Instead, we have to use the entity manager directly which gives us more control over whether to insert or update an
   * entity.
   */
  private final EntityManager entityManager;

  private ArmyStatisticsService armyStatisticsService;
  /** Wire ourselves for calling inner methods using {@link Transactional}. */
  @Autowired
  @VisibleForTesting
  GameService gameService;

  /**
   * A list of games which can not yet be rated because there are other games that need to finish and be rated first.
   * This is the case if one of a game's players has also participated in another game, which has not yet finished.
   * Without waiting, the rating update of the second game would get overridden as soon as the first game ends.
   */
  private Collection<Game> gamesAwaitingRatingUpdate;

  public GameService(GameRepository gameRepository, MeterRegistry meterRegistry, ClientService clientService,
                     MapService mapService, ModService modService, PlayerService playerService,
                     RatingService ratingService, ServerProperties properties, DivisionService divisionService,
                     ActiveGameRepository activeGameRepository, EntityManager entityManager, ArmyStatisticsService armyStatisticsService) {
    this.gameRepository = gameRepository;
    this.clientService = clientService;
    this.mapService = mapService;
    this.modService = modService;
    this.playerService = playerService;
    this.ratingService = ratingService;
    this.divisionService = divisionService;
    this.activeGameRepository = activeGameRepository;
    this.entityManager = entityManager;
    this.armyStatisticsService = armyStatisticsService;
    lastGameId = new AtomicInteger(0);
    gamesAwaitingRatingUpdate = new ArrayList<>();

    validityVoters = Arrays.asList(
      ValidityVoter.isRankedVoter(modService),
      ValidityVoter.victoryConditionVoter(modService),
      ValidityVoter.freeForAllVoter(),
      ValidityVoter.evenTeamsVoter(modService),
      ValidityVoter.fogOfWarVoter(),
      ValidityVoter.cheatsEnabledVoter(),
      ValidityVoter.prebuiltUnitsVoter(),
      ValidityVoter.teamSpawnVoter(modService),
      ValidityVoter.civiliansRevealedVoter(modService),
      ValidityVoter.difficultyVoter(modService),
      ValidityVoter.expansionDisabledVoter(modService),
      ValidityVoter.noRushVoter(),
      ValidityVoter.restrictedUnitsVoter(),
      ValidityVoter.rankedMapVoter(),
      ValidityVoter.desyncVoter(),
      ValidityVoter.mutualDrawVoter(),
      ValidityVoter.singlePlayerVoter(),
      ValidityVoter.gameResultVoter(),
      ValidityVoter.gameLengthVoter(properties),
      ValidityVoter.teamsUnlockedVoter(),
      ValidityVoter.hasAiVoter()
    );

    Gauge.builder(Metrics.GAMES, activeGameRepository, CrudRepository::count)
      .description("The number of games that are currently known by the server.")
      .tag(TAG_GAME_STATE, "")
      .register(meterRegistry);

    Builder<GameState, AtomicInteger> gameStateCountersBuilder = ImmutableMap.builder();
    for (GameState gameState : GameState.values()) {
      AtomicInteger atomicInteger = new AtomicInteger();
      gameStateCountersBuilder.put(gameState, atomicInteger);

      Gauge.builder(Metrics.GAMES, atomicInteger, AtomicInteger::get)
        .description("The number of games that are currently known by the server and in state " + gameState.name())
        .tag(TAG_GAME_STATE, gameState.name())
        .register(meterRegistry);
    }
    gameStateCounters = gameStateCountersBuilder.build();

    Builder<PlayerGameState, AtomicInteger> playerGameStateCountersBuilder = ImmutableMap.builder();
    for (PlayerGameState playerGameState : PlayerGameState.values()) {
      AtomicInteger atomicInteger = new AtomicInteger();
      playerGameStateCountersBuilder.put(playerGameState, atomicInteger);

      Gauge.builder(Metrics.PLAYERS, atomicInteger, AtomicInteger::get)
        .description("The number of players that are currently online and whose game is in state " + playerGameState.name())
        .tag(TAG_PLAYER_GAME_STATE, playerGameState.name())
        .register(meterRegistry);
    }
    playerGameStateCounters = playerGameStateCountersBuilder.build();
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
  public CompletableFuture<Game> createGame(String title, String featuredModName, String mapFileName,
                                            String password, GameVisibility visibility,
                                            Integer minRating, Integer maxRating, Player player, LobbyMode lobbyMode,
                                            Optional<List<GameParticipant>> presetParticipants) {

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
    game.setPresetParticipants(presetParticipants);
    game.setHost(player);
    modService.getFeaturedMod(featuredModName)
      .map(game::setFeaturedMod)
      .orElseThrow(() -> new RequestException(ErrorCode.INVALID_FEATURED_MOD, featuredModName));
    game.setTitle(title);
    // FIXME I think this is still broken
    mapService.findMap(mapFileName).ifPresent(game::setMapVersion);
    game.setMapFolderName(mapFileName);
    game.setPassword(password);
    Optional.ofNullable(visibility).ifPresent(game::setGameVisibility);
    game.setMinRating(minRating);
    game.setMaxRating(maxRating);
    game.setLobbyMode(lobbyMode);

    activeGameRepository.save(game);
    gameStateCounters.get(game.getState()).incrementAndGet();

    log.debug("Player '{}' created game '{}'", player, game);

    clientService.startGameProcess(game, player);
    player.setCurrentGame(game);
    changePlayerGameState(player, PlayerGameState.INITIALIZING);

    CompletableFuture<Game> gameJoinedFuture = new CompletableFuture<>();
    player.setGameFuture(gameJoinedFuture);

    return game.getJoinableFuture();
  }

  /**
   * Tells the client to start the game process and sets the player's current game to it.
   *
   * @return a future that will be completed as soon as the player's game has been started and entered {@link
   * GameState#OPEN}. Be aware that there are various reasons for the game to never start (crash, disconnect, abort) so
   * never wait without a timeout.
   */
  public CompletableFuture<Game> joinGame(int gameId, String password, Player player) {
    Requests.verify(player.getCurrentGame() == null, ErrorCode.ALREADY_IN_GAME);

    Game game = getActiveGame(gameId).orElseThrow(() -> new IllegalArgumentException("No such game: " + gameId));
    Requests.verify(game.getState() == GameState.OPEN, ErrorCode.GAME_NOT_JOINABLE);
    Requests.verify(game.getPassword() == null || game.getPassword().equals(password), ErrorCode.INVALID_PASSWORD);

    log.debug("Player '{}' joins game '{}'", player, gameId);
    clientService.startGameProcess(game, player);

    player.setCurrentGame(game);
    changePlayerGameState(player, PlayerGameState.INITIALIZING);

    CompletableFuture<Game> gameJoinedFuture = new CompletableFuture<>();
    player.setGameFuture(gameJoinedFuture);

    return gameJoinedFuture;
  }

  /**
   * Updates the game state of a player's game.
   */
  @Transactional
  public void updatePlayerGameState(PlayerGameState newState, Player player) {
    Game game = player.getCurrentGame();
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
      case CLOSED:
        onPlayerGameClosed(player, game);
        break;
      case IDLE:
        log.warn("Ignoring state '{}' from player '{}' for game '{}' (should be handled by the client)", newState, player, game);
        break;
      default:
        throw new ProgrammingError("Uncovered state: " + newState);
    }
  }

  @Transactional
  public void updateUnfinishedGamesValidity(Validity validity) {
    log.debug("Invalidating unfinished games validity to: {}", validity);
    gameRepository.updateUnfinishedGamesValidity(validity);
  }

  public void playerDisconnected(Player reporter, int disconnectedPlayerId) {
    log.debug("Player '{}' reported disconnect of player with ID '{}'", reporter, disconnectedPlayerId);
  }

  private void processGamesAwaitingRatingUpdate() {
    gamesAwaitingRatingUpdate.stream()
      .filter(game -> !hasRatingDependentGame(game) && game.getStartTime() != null)
      .sorted(Comparator.comparing(Game::getStartTime))
      .forEach(this::updateRatingsIfValid);
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
    } else if (OPTION_SCENARIO_FILE.equals(key)) {
      game.setMapFolderName(((String) value).replace("//", "/").replace("\\", "/").split("/")[2]);
    } else if (OPTION_TITLE.equals(key)) {
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

    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
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

    if (!OPTION_ARMY.equals(key)) {
      log.trace("Ignoring option '{}' = '{}' for AI '{}' in game '{}' because only the option '{}' is currently sent with the correct, final AI name",
        OPTION_ARMY, key, value, aiName, game.getId());
      return;
    }

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
      .filter(entry -> Objects.equals(entry.getValue().get(OPTION_START_SPOT), slotId))
      .map(Entry::getKey)
      .collect(Collectors.toList())
      .forEach(playerId -> {
        log.trace("Removing options for player '{}' in game '{}'", playerId, game);
        game.getPlayerOptions().remove(playerId);
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
    game.getReportedArmyResults()
      .computeIfAbsent(reporter.getId(), playerId -> new HashMap<>())
      .compute(armyId, (integer, armyResult) -> {
        if (armyResult == null) {
          return ArmyResult.of(armyId, Outcome.UNKNOWN, score);
        }
        return ArmyResult.of(armyId, armyResult.getOutcome(), score);
      });
  }

  public void reportArmyOutcome(Player reporter, int armyId, Outcome outcome, int score) {
    Game game = reporter.getCurrentGame();
    if (game == null) {
      log.warn("Army score reported by player w/o game: {}", reporter);
      return;
    }

    if (!hasArmy(game, armyId)) {
      log.warn("Player '{}' reported outcome '{}' with score '{}' for unknown army '{}' in game '{}'", reporter, outcome, armyId, game);
      return;
    }

    log.debug("Player '{}' reported result for army '{}' in game '{}': {}, {}", reporter, armyId, game, outcome, score);
    game.getReportedArmyResults()
      .computeIfAbsent(reporter.getId(), playerId -> new HashMap<>())
      .put(armyId, ArmyResult.of(armyId, outcome, score));
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
    playerGameStateCounters.get(event.getPlayer().getGameState()).incrementAndGet();

    List<GameResponse> gameResponses = activeGamesStream()
      .map(this::toResponse).collect(Collectors.toList());

    clientService.sendGameList(new GameResponses(gameResponses), event.getPlayer()
    );
  }

  @NotNull
  private Stream<Game> activeGamesStream() {
    return StreamSupport.stream(activeGameRepository.findAll().spliterator(), false);
  }

  @EventListener
  public void onPlayerOfflineEvent(PlayerOfflineEvent event) {
    playerGameStateCounters.get(event.getPlayer().getGameState()).decrementAndGet();
  }

  public void removePlayer(Player player) {
    Optional.ofNullable(player.getCurrentGame()).ifPresent(game -> removePlayer(game, player));
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

    player.setGameFuture(CompletableFuture.completedFuture(game));
    addPlayer(game, player);

    changePlayerGameState(player, PlayerGameState.INITIALIZING);
    changePlayerGameState(player, PlayerGameState.LOBBY);
    if (gameState == GameState.PLAYING) {
      changePlayerGameState(player, PlayerGameState.LAUNCHING);
    }
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

  public void reportGameEnded(Player reporter) {
    Requests.verify(reporter.getCurrentGame() != null, ErrorCode.NOT_IN_A_GAME);

    Game game = reporter.getCurrentGame();
    game.getPlayerIdsWhoReportedGameEnd().add(reporter.getId());

    long missingGameEndedReports = reporter.getCurrentGame().getConnectedPlayers().values().stream()
      .filter(player -> !game.getPlayerIdsWhoReportedGameEnd().contains(player.getId()))
      .count();

    if (missingGameEndedReports > 0) {
      return;
    }

    gameService.onGameEnded(game);
  }

  private void changePlayerGameState(Player player, PlayerGameState newState) {
    playerGameStateCounters.get(player.getGameState()).decrementAndGet();
    player.setGameState(newState);
    playerGameStateCounters.get(player.getGameState()).incrementAndGet();
  }

  private void addPlayer(Game game, Player player) {
    game.getConnectedPlayers().put(player.getId(), player);

    if (modService.isLadder1v1(game.getFeaturedMod())) {
      if (player.getLadder1v1Rating() == null) {
        ratingService.initLadder1v1Rating(player);
      }
      player.setRatingWithinCurrentGame(player.getLadder1v1Rating());
    } else {
      if (player.getGlobalRating() == null) {
        ratingService.initGlobalRating(player);
      }
      player.setRatingWithinCurrentGame(player.getGlobalRating());
    }

    player.setCurrentGame(game);
    player.getGameFuture().complete(game);

    markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
  }

  private void removePlayer(Game game, Player player) {
    log.debug("Removing player '{}' from game '{}'", player, game);

    int playerId = player.getId();
    changePlayerGameState(player, PlayerGameState.NONE);
    player.setCurrentGame(null);
    player.getGameFuture().cancel(false);

    Map<Integer, Player> connectedPlayers = game.getConnectedPlayers();
    connectedPlayers.remove(playerId);

    clientService.disconnectPlayerFromGame(player.getId(), connectedPlayers.values());

    // Checking for GameState.INITIALIZING isn't necessary since in this case, connectedPlayers will already be empty
    if (game.getState() == GameState.OPEN && game.getHost().equals(player)) {
      // A copy of the connected players is required as otherwise we run into a ConcurrentModificationException
      new ArrayList<>(connectedPlayers.values()).forEach(connectedPlayer -> removePlayer(game, connectedPlayer));
    }

    if (connectedPlayers.isEmpty()) {
      switch (game.getState()) {
        case INITIALIZING:
        case OPEN:
          onGameCancelled(game);
          break;

        case PLAYING:
          gameService.onGameEnded(game);
          break;

        default:
          // Nothing to do
      }
    } else {
      markDirty(game, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY);
    }
  }

  private void onPlayerGameEnded(Player player, Game game) {
    log.debug("Player '{}' ended game: {}", player, game);
    if (game.getState() != GameState.ENDED) {
      gameService.onGameEnded(game);
    }
  }

  private void onPlayerGameClosed(Player player, Game game) {
    log.debug("Player '{}' closed game: {}", player, game);
    removePlayer(game, player);
  }

  private void onGameCancelled(Game game) {
    log.debug("Game cancelled: {}", game);
    onGameClosed(game);
  }

  /**
   * Returns {@code true} if there is at least one active game that is older than the specified game and contains at
   * least one player who also participated in the specified game.
   */
  private boolean hasRatingDependentGame(Game game) {
    return activeGamesStream()
      .anyMatch(activeGame -> !Objects.equals(activeGame, game)
        && activeGame.getStartTime() != null
        && activeGame.getState() == GameState.PLAYING
        && activeGame.getStartTime().isBefore(game.getStartTime())
        && activeGame.getPlayerStats().keySet().stream().anyMatch(playerId -> game.getPlayerStats().containsKey(playerId))
      );
  }

  private void enqueueForRatingUpdate(Game game) {
    gamesAwaitingRatingUpdate.add(game);
  }

  private void onGameClosed(Game game) {
    if (game.getState() == GameState.CLOSED) {
      return;
    }

    changeGameState(game, GameState.CLOSED);
    markDirty(game, Duration.ZERO, Duration.ZERO);

    activeGameRepository.delete(game);
  }

  private GameResultMessage buildGameResult(Game game, Map<Integer, ArmyResult> playerIdToResult) {
    Set<PlayerResult> playerResults = new HashSet<>();

    GameResultMessage gameResultMessage = new GameResultMessage()
      .setGameId(game.getId())
      .setPlayerResults(playerResults)
      .setDraw(false);

    for (Map.Entry<Integer, ArmyResult> playerResultEntry : playerIdToResult.entrySet()) {
      playerResults.add(
        new PlayerResult()
          .setPlayerId(playerResultEntry.getKey())
          .setAcuKilled(false) // TODO: Add a way to actually find this out
          .setWinner(playerResultEntry.getValue().getOutcome() == Outcome.VICTORY)
      );

      if (playerResultEntry.getValue().getOutcome() == Outcome.DRAW) {
        gameResultMessage.setDraw(true);
      }
    }

    return gameResultMessage;
  }

  /**
   * Determines the final army results and updates the players' scores with them.
   */
  private void settlePlayerScores(Game game, Map<Integer, ArmyResult> armyIdToResult) {
    for (GamePlayerStats gamePlayerStats : game.getPlayerStats().values()) {
      Integer playerId = gamePlayerStats.getPlayer().getId();
      Integer armyScore = Optional.ofNullable(game.getPlayerOptions())
        .map(playerOptions -> playerOptions.get(playerId))
        .map(options -> (Integer) options.get(OPTION_ARMY))
        .map(armyIdToResult::get)
        .map(ArmyResult::getScore)
        .orElse(null);

      gamePlayerStats.setScore(armyScore).setScoreTime(Instant.now());
    }
  }

  /**
   * Returns the active game with the specified ID, if such a game exists. A game is considered active as soon as it's
   * being hosted and until it finished.
   */
  @VisibleForTesting
  Optional<Game> getActiveGame(int id) {
    return activeGameRepository.findById(id);
  }

  @Transactional
  protected void onGameEnded(Game game) {
    if (game.getState() == GameState.ENDED) {
      return;
    }

    log.debug("Game ended: {}", game);

    GameState previousState = game.getState();
    game.setEndTime(Instant.now());
    try {
      changeGameState(game, GameState.ENDED);
    } catch (IllegalStateException e) {
      // Don't prevent a programming error from ending the game, but let us know about it.
      log.warn("Illegally tried to transition game '{}' from state '{}' to '{}'", game, previousState, GameState.ENDED, e);
    }

    // Games can also end before they even started, in which case we stop processing it
    if (previousState != GameState.PLAYING) {
      return;
    }

    updateGameValidity(game);
    enqueueForRatingUpdate(game);
    processGamesAwaitingRatingUpdate();
    Optional.ofNullable(game.getMapVersion()).ifPresent(mapVersion -> mapService.incrementTimesPlayed(mapVersion.getMap()));


    Map<Integer, ArmyResult> armyIdToResult = findMostReportedCompleteArmyResultsReportedByConnectedPlayers(game);
    Map<Integer, ArmyResult> playerIdToResult = mapArmyResultsToPlayerIds(game, armyIdToResult);
    GameResultMessage gameResultMessage = buildGameResult(game, playerIdToResult);

    settlePlayerScores(game, armyIdToResult);
    clientService.broadcastGameResult(gameResultMessage);
    updateDivisionScoresIfValid(game);
    gameRepository.save(game);

    try {
      game.getPlayerStats().values().forEach(stats -> {
        Player player = stats.getPlayer();
        armyStatisticsService.process(player, game);
      });
    } catch (Exception e) {
      log.warn("Army statistics could not be updated", e);
    }

    if (game.getConnectedPlayers().isEmpty()) {
      onGameClosed(game);
    }
  }

  /**
   * Takes a map of {@code armyId -> ArmyResult} and maps all army IDs to player IDs.
   */
  @VisibleForTesting
  Map<Integer, ArmyResult> mapArmyResultsToPlayerIds(Game game, Map<Integer, ArmyResult> armyIdToResult) {
    Map<Integer, ArmyResult> playerIdToResult = new HashMap<>();
    for (GamePlayerStats gamePlayerStats : game.getPlayerStats().values()) {
      Integer playerId = gamePlayerStats.getPlayer().getId();
      Optional.ofNullable(game.getPlayerOptions())
        .map(playerOptions -> playerOptions.get(playerId))
        .map(options -> (Integer) options.get(OPTION_ARMY))
        .map(armyIdToResult::get)
        .ifPresent(armyResult -> playerIdToResult.put(playerId, armyResult));
    }

    return playerIdToResult;
  }

  /**
   * Finds the {@link ArmyResult ArmyResults} that have been reported most often, mapped by army ID. Only respects
   * reports of players who are still connected and have reported a score as well as an outcome.
   */
  @VisibleForTesting
  Map<Integer, ArmyResult> findMostReportedCompleteArmyResultsReportedByConnectedPlayers(Game game) {
    Map<ArmyResult, Long> completeArmyResultToOccurrence = game.getReportedArmyResults().entrySet().stream()
      .filter(playerIdToResults -> game.getConnectedPlayers().containsKey(playerIdToResults.getKey()))
      .flatMap(playerIdToReportedResults -> playerIdToReportedResults.getValue().values().stream())
      .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

    Map<Integer, ArmyResult> armyIdsToMostReportedScore = new HashMap<>();
    Map<Integer, Long> mostOccurrencesByArmyId = new HashMap<>();

    completeArmyResultToOccurrence.forEach((armyScore, occurrence) -> {
      int armyId = armyScore.getArmyId();
      Long mostOccurrence = mostOccurrencesByArmyId.get(armyId);
      if (mostOccurrence == null || occurrence > mostOccurrence) {
        mostOccurrencesByArmyId.put(armyId, occurrence);
        armyIdsToMostReportedScore.put(armyId, armyScore);
      }
    });
    return armyIdsToMostReportedScore;
  }


  private void updateRatingsIfValid(Game game) {
    if (game.getValidity() != Validity.VALID && !game.isRatingEnforced()) {
      return;
    }
    RatingType ratingType = modService.isLadder1v1(game.getFeaturedMod()) ? RatingType.LADDER_1V1 : RatingType.GLOBAL;
    ratingService.updateRatings(game.getPlayerStats().values(), NO_TEAM_ID, ratingType);
  }

  private void updateDivisionScoresIfValid(Game game) {
    if (game.getValidity() != Validity.VALID && !game.isRatingEnforced()) {
      log.trace("Skipping update of division scores for invalid game: {}", game);
      return;
    }

    if (!modService.isLadder1v1(game.getFeaturedMod())) {
      log.trace("Skipping update of division scores for non-ladder1v1 game: {}", game);
      return;
    }

    log.trace("Updating division scores for game: {}", game);

    Assert.state(game.getConnectedPlayers().size() == 2, "A ladder1v1 game must have exactly 2 players");

    Iterator<Player> playerIterator = game.getConnectedPlayers().values().iterator();
    Player playerOne = playerIterator.next();
    Player playerTwo = playerIterator.next();

    Player winner = null;
    if (!game.isMutuallyAgreedDraw()) {
      winner = game.getPlayerStats().values().stream()
        .filter(gamePlayerStats -> gamePlayerStats.getScore() != null)
        .max(Comparator.comparingInt(GamePlayerStats::getScore))
        .map(GamePlayerStats::getPlayer)
        .orElse(null);
      log.trace("Game '{}' did not end with mutual draw, winner is: {}", game, winner);
    } else {
      log.trace("Game '{}' ended with mutual draw", game);
    }

    log.debug("Posting results for game '{}', playerOne: '{}', playerTwo: '{}', winner: '{}'", game, playerOne, playerTwo, winner);
    divisionService.postResult(playerOne, playerTwo, winner);
  }

  private void onGameLaunching(Player reporter, Game game) {
    if (!Objects.equals(game.getHost(), reporter)) {
      // TODO do non-hosts send this? If not, log to WARN, else ignore if not host
      log.warn("Player '{}' reported launch for game '{}' but host is '{}'.", reporter, game, game.getHost());
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
    GameState oldState = game.getState();
    if (oldState != GameState.CLOSED) {
      gameStateCounters.get(oldState).decrementAndGet();
    }
    game.setState(newState);
    if (newState != GameState.CLOSED) {
      gameStateCounters.get(newState).incrementAndGet();
    }
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
      Ladder1v1Rating ladder1v1Rating = player.getLadder1v1Rating();
      Assert.state(ladder1v1Rating != null, "Expected ladder1v1 rating to be set");

      gamePlayerStats.setDeviation(ladder1v1Rating.getDeviation());
      gamePlayerStats.setMean(ladder1v1Rating.getMean());
    } else {
      GlobalRating globalRating = player.getGlobalRating();
      Assert.state(globalRating != null, "Expected global rating to be set");

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
    if (game.getConnectedPlayers().containsKey(player.getId())) {
      log.warn("Player '{}' entered state '{}' but is already connected", player, PlayerGameState.LOBBY);
      return;
    }
    log.debug("Player '{}' entered state: {}", player, PlayerGameState.LOBBY);
    if (Objects.equals(game.getHost(), player)) {
      changeGameState(game, GameState.OPEN);
      clientService.hostGame(game, player);
    } else {
      connectToHost(game, player);
      game.getConnectedPlayers().values().stream()
        .filter(connectedPlayer -> !Objects.equals(game.getHost(), connectedPlayer))
        .forEach(otherPlayer -> connectPeers(player, otherPlayer));
    }
    addPlayer(game, player);
  }

  private void connectToHost(Game game, Player player) {
    clientService.connectToHost(player, game);
    clientService.connectToPeer(game.getHost(), player, true);
  }

  private void connectPeers(Player player, Player otherPlayer) {
    if (player.equals(otherPlayer)) {
      log.warn("Player '{}' should not be told to connect to himself", player);
      return;
    }
    clientService.connectToPeer(player, otherPlayer, true);
    clientService.connectToPeer(otherPlayer, player, false);
  }

  private boolean hasArmy(Game game, int armyId) {
    return Stream.concat(game.getPlayerOptions().values().stream(), game.getAiOptions().values().stream())
      .filter(options -> options.containsKey(OPTION_ARMY))
      .map(options -> (int) options.get(OPTION_ARMY))
      .anyMatch(id -> id == armyId);
  }

  private void markDirty(Game game, Duration minDelay, Duration maxDelay) {
    clientService.broadcastDelayed(toResponse(game), minDelay, maxDelay, gameResponse -> "game-" + gameResponse.getId(), GAME_RESPONSE_AGGREGATOR);
  }

  private GameResponse toResponse(Game game) {
    return new GameResponse(
      game.getId(),
      game.getTitle(),
      game.getGameVisibility(),
      !Strings.isNullOrEmpty(game.getPassword()),
      game.getState(),
      game.getFeaturedMod().getTechnicalName(),
      toSimMods(game.getSimMods()),
      game.getMapFolderName(),
      game.getHost().getLogin(),
      toPlayers(game),
      game.getMaxPlayers(),
      Optional.ofNullable(game.getStartTime()).orElse(null),
      game.getMinRating(),
      game.getMaxRating(),
      getFeaturedModVersion(game.getFeaturedMod()),
      toFeaturedModFileVersions(game.getFeaturedMod())
    );
  }

  private int getFeaturedModVersion(FeaturedMod featuredMod) {
    return modService.getLatestFileVersions(featuredMod).stream()
      .max(Comparator.comparingInt(FeaturedModFile::getVersion))
      .map(FeaturedModFile::getVersion)
      .orElseThrow(() -> new IllegalStateException("No file version could be found for mod: " + featuredMod.getTechnicalName()));
  }

  private List<FeaturedModFileVersion> toFeaturedModFileVersions(FeaturedMod featuredMod) {
    return modService.getLatestFileVersions(featuredMod).stream()
      .map(featuredModFile -> new FeaturedModFileVersion(featuredModFile.getFileId(), featuredModFile.getVersion()))
      .collect(Collectors.toList());
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

        return new GameResponse.Player(player.getId(), player.getLogin(), team);
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
    Assert.state(game.getState() == GameState.PLAYING || game.getState() == GameState.ENDED,
      "Validity of game '" + game + "' can't be set while in state: " + game.getState());

    validityVoters.stream()
      .map(voter -> voter.apply(game))
      .filter(validity -> validity != Validity.VALID)
      .findFirst()
      .ifPresent(game::setValidity);
  }
}
