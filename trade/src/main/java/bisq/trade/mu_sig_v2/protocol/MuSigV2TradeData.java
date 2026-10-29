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

import bisq.account.accounts.AccountPayload;
import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.trade.TradeRole;
import static com.google.common.base.Preconditions.checkArgument;

/** Fixed trade facts. Child flow snapshots belong to the trade document, outside this flow's data. */
public record MuSigV2TradeData(MuSigContract contract,
                               Identity myIdentity,
                               TradeRole myRole,
                               String protocolVersion,
                               AccountPayload<?> myAccountPayload) implements PersistableProto {
    @Override
    public bisq.trade.protobuf.MuSigV2TradeData.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigV2TradeData.newBuilder()
                .setContract(contract.toProto(serializeForHash))
                .setMyIdentity(myIdentity.toProto(serializeForHash))
                .setMyRole(myRole.toProtoEnum())
                .setProtocolVersion(protocolVersion)
                .setMyAccountPayload(myAccountPayload.toProto(serializeForHash));
    }

    @Override
    public bisq.trade.protobuf.MuSigV2TradeData toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2TradeData fromProto(bisq.trade.protobuf.MuSigV2TradeData proto) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasContract() && proto.hasMyIdentity() && proto.hasMyAccountPayload(),
                    "Incomplete trade data");
            return new MuSigV2TradeData(MuSigContract.fromProto(proto.getContract()),
                    Identity.fromProto(proto.getMyIdentity()),
                    TradeRole.fromProto(proto.getMyRole()),
                    proto.getProtocolVersion(),
                    AccountPayload.fromProto(proto.getMyAccountPayload()));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
