/** Reserved gate-conversation missions.
 *
 * Contract: a gate-conversation mission opens a comlink lobby and only then advertises
 * its gate. It composes the existing comlink and gate readers; it does not duplicate
 * their schemas.
 *
 * Evidence still required: the lobby lifecycle, the transition from the closing
 * comlink response to gate advertisement, and the native callbacks. The imported
 * dialogue and route data are not proof of playability. */
package org.tortodrone.gameplay.mission.gateconversation;
