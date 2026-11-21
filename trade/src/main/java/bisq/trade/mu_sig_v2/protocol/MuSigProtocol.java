/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.trade.mu_sig_v2.protocol;

import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineDefinition;

/**
 * Base of the MuSig V2 protocols. Each protocol defines one flow of a trade. This class holds what belongs to all of
 * them: the protocol version, the list of the flows, and the event family and the two contexts which every flow uses.
 *
 * @param <S> flow state family
 * @param <D> accumulated flow data
 */
public abstract class MuSigProtocol<S, D>
        extends StateMachineDefinition<S, D, Event, MuSigReadOnlyContext, MuSigProtocolContext> {
    public static final String VERSION = "1.0.0";

    /**
     * The flows of a trade. The trade store saves each flow under its name. A renamed constant makes every trade
     * unreadable which was saved before.
     */
    public enum Flow { TRADE, EXECUTION, MEDIATION, MY_MEDIATION_DECISION, PEER_MEDIATION_DECISION }
}
