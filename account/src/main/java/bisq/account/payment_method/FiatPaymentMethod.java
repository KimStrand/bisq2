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
import bisq.common.asset.FiatCurrencyRepository;
import bisq.common.locale.Country;
import bisq.i18n.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@ToString(callSuper = true)
@Getter
@EqualsAndHashCode(callSuper = true)
public class FiatPaymentMethod extends NationalCurrencyPaymentMethod<FiatPaymentRail> {
    public static FiatPaymentMethod fromPaymentRail(FiatPaymentRail fiatPaymentRail) {
        return new FiatPaymentMethod(fiatPaymentRail);
    }

    public static FiatPaymentMethod fromCustomName(String customName) {
        return new FiatPaymentMethod(customName);
    }

    public static FiatPaymentMethod fromPaymentRailName(String paymentRailName) {
        return new FiatPaymentMethod(FiatPaymentRail.valueOf(paymentRailName));
    }


    private FiatPaymentMethod(FiatPaymentRail paymentRail) {
        super(paymentRail);

        verify();
    }

    private FiatPaymentMethod(String customName) {
        super(customName);

        verify();
    }

    @Override
    public bisq.account.protobuf.PaymentMethod.Builder getBuilder(boolean serializeForHash) {
        return getPaymentMethodBuilder(serializeForHash).setFiatPaymentMethod(bisq.account.protobuf.FiatPaymentMethod.newBuilder());
    }

    @Override
    public bisq.account.protobuf.PaymentMethod toProto(boolean serializeForHash) {
        return resolveProto(serializeForHash);
    }

    public static FiatPaymentMethod fromProto(bisq.account.protobuf.PaymentMethod proto) {
        return FiatPaymentMethodUtil.getPaymentMethod(proto.getPaymentRailName());
    }

    @Override
    protected FiatPaymentRail getCustomPaymentRail() {
        return FiatPaymentRail.CUSTOM;
    }

    @Override
    public List<? extends Asset> getSupportedCurrencies() {
        return paymentRail.getSupportedCurrencies();
    }

    public List<Country> getSupportedCountries() {
        return paymentRail.getSupportedCountries();
    }

    public String getSupportedCurrencyCodesAsDisplayString() {
        if (supportsAllFiatCurrencies()) {
            return Res.get("paymentAccounts.allCurrencies");
        } else {
            List<String> currencyCodes = getSupportedCurrencyCodes();
            if (currencyCodes.size() == 1) {
                return currencyCodes.stream().findFirst().orElseThrow();
            } else {
                return String.join(", ", currencyCodes);
            }
        }
    }

    public String getSupportedCurrencyDisplayNameAndCodeAsDisplayString() {
        if (supportsAllFiatCurrencies()) {
            return Res.get("paymentAccounts.allCurrencies");
        } else {
            List<String> displayNameAndCode = getSupportedCurrencyDisplayNameAndCode().stream().sorted().collect(Collectors.toList());
            if (displayNameAndCode.size() == 1) {
                return displayNameAndCode.stream().findFirst().orElseThrow();
            } else {
                return String.join(", ", displayNameAndCode);
            }
        }
    }

    private boolean supportsAllFiatCurrencies() {
        List<String> currencyCodes = getSupportedCurrencyCodes().stream().sorted().toList();
        List<String> allCurrencies = FiatCurrencyRepository.getAllFiatCurrencyCodes().stream().sorted().toList();
        return currencyCodes.equals(allCurrencies);
    }
}
