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

package bisq.account.payment_method;

import bisq.common.asset.Asset;
import bisq.common.asset.StableCoin;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
@ToString(callSuper = true)
@Getter
@EqualsAndHashCode(callSuper = true)
public class StableCoinPaymentMethod extends NationalCurrencyPaymentMethod<StableCoinPaymentRail> {
    public static StableCoinPaymentMethod fromPaymentRail(StableCoinPaymentRail paymentRail) {
        return new StableCoinPaymentMethod(paymentRail);
    }

    public static StableCoinPaymentMethod fromCustomName(String customName) {
        // StablecoinPaymentMethod does not support custom paymentRails
        //TODO can be removed once stable coin domain is completed and confirmed that this is not needed
        return null;
    }

    private StableCoinPaymentMethod(StableCoinPaymentRail paymentRail) {
        super(paymentRail);

        verify();
    }

    @Override
    public bisq.account.protobuf.PaymentMethod.Builder getBuilder(boolean serializeForHash) {
        return getPaymentMethodBuilder(serializeForHash).setStableCoinPaymentMethod(bisq.account.protobuf.StableCoinPaymentMethod.newBuilder());
    }

    @Override
    public bisq.account.protobuf.PaymentMethod toProto(boolean serializeForHash) {
        return resolveProto(serializeForHash);
    }

    public static StableCoinPaymentMethod fromProto(bisq.account.protobuf.PaymentMethod proto) {
        return Optional.ofNullable(
                        StablecoinPaymentMethodUtil.getPaymentMethod(proto.getPaymentRailName()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown stable-coin payment method: " + proto.getPaymentRailName()));
    }

    @Override
    protected StableCoinPaymentRail getCustomPaymentRail() {
        // StablecoinPaymentMethod does not support custom paymentRails
        return null;
    }

    @Override
    public List<Asset> getSupportedCurrencies() {
        return paymentRail.getTradeCurrencies();
    }

    public String getCode() {
        return getStableCoin().getCode();
    }

    public String getName() {
        return getStableCoin().getName();
    }

    public String getPegCurrencyCode() {
        return getStableCoin().getPegCurrencyCode();
    }

    public StableCoin.TokenStandard getTokenStandard() {
        return getStableCoin().getTokenStandard();
    }

    public StableCoin.Network getNetwork() {
        return getStableCoin().getNetwork();
    }


    private StableCoin getStableCoin() {
        return paymentRail.getStableCoin();
    }
}
