package bisq.trade;

import bisq.common.proto.ProtoEnum;
import bisq.common.proto.ProtobufUtils;

public enum DisputeState implements ProtoEnum {
    NO_DISPUTE,
    MEDIATION_OPEN,
    MEDIATION_CLOSED,
    MEDIATION_RE_OPENED,
    ARBITRATION_OPEN,
    ARBITRATION_CLOSED;

    @Override
    public bisq.trade.protobuf.DisputeState toProtoEnum() {
        return bisq.trade.protobuf.DisputeState.valueOf(getProtobufEnumPrefix() + name());
    }

    public static DisputeState fromProto(bisq.trade.protobuf.DisputeState proto) {
        return ProtobufUtils.enumFromProto(DisputeState.class, proto.name(), NO_DISPUTE);
    }
}
