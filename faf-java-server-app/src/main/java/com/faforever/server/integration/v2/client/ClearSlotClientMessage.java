package com.faforever.server.integration.v2.client;

import com.faforever.server.annotations.V2ClientNotification;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Message sent from the client to the server to inform the server that a player slot within the game lobby has been
 * cleared.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@V2ClientNotification
class ClearSlotClientMessage extends V2ClientMessage {

  public static final String TYPE_NAME = "clearSlot";

  /** The ID of the game slot that has been cleared. */
  private int slotId;
}
