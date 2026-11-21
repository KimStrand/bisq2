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

package bisq.trade.mu_sig_v2.protocol.mediation;

import bisq.common.processing.EffectFailureRule;
import bisq.common.processing.PersistableEffect;
import bisq.common.proto.UnresolvableProtobufException;

/** Enables trader chat and trade-log delivery to the contract's mediator after checkpointing. */
public record ActivateMediationChat() implements PersistableEffect {
    /**
     * The trade must not stop because its chat cannot enter mediation mode. Without that mode, chat lines do not reach
     * the mediator, so the lane logs the failure.
     */
    @Override
    public EffectFailureRule failureRule() {
        return EffectFailureRule.BEST_EFFORT;
    }

    @Override
    public bisq.trade.protobuf.ActivateMediationChat.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.ActivateMediationChat.newBuilder();
    }

    @Override
    public bisq.trade.protobuf.ActivateMediationChat toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static ActivateMediationChat fromProto(bisq.trade.protobuf.ActivateMediationChat proto) throws UnresolvableProtobufException {
        try {
            return new ActivateMediationChat();
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
