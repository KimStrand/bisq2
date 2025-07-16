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

package bisq.account.accounts.fiat;

import bisq.account.accounts.MultiCurrencyAccountPayload;
import bisq.account.accounts.util.AccountDataDisplayStringBuilder;
import bisq.account.payment_method.FiatPaymentMethod;
import bisq.account.payment_method.FiatPaymentRail;
import bisq.account.protobuf.AccountPayload;
import bisq.common.validation.EmailValidation;
import bisq.common.validation.PaymentAccountValidation;
import bisq.i18n.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

@Getter
@Slf4j
@ToString
@EqualsAndHashCode(callSuper = true)
public final class UpholdAccountPayload extends CountryBasedAccountPayload implements MultiCurrencyAccountPayload {
    private final List<String> selectedCurrencyCodes;
    private final String holderName;
    private final String accountId;

    public UpholdAccountPayload(String id,
                                String countryCode,
                                List<String> selectedCurrencyCodes,
                                String holderName,
                                String accountId
    ) {
        super(id, countryCode);
        this.selectedCurrencyCodes = selectedCurrencyCodes;
        this.holderName = holderName;
        this.accountId = accountId;

        verify();
    }

    @Override
    public void verify() {
        super.verify();
        PaymentAccountValidation.validateCurrencyCodes(selectedCurrencyCodes);
        checkArgument(EmailValidation.isValid(accountId));
        PaymentAccountValidation.validateHolderName(holderName);
    }

    @Override
    protected bisq.account.protobuf.CountryBasedAccountPayload.Builder getCountryBasedAccountPayloadBuilder(boolean serializeForHash) {
        return super.getCountryBasedAccountPayloadBuilder(serializeForHash)
                .setUpholdAccountPayload(toUpholdAccountPayloadProto(serializeForHash));
    }

    private bisq.account.protobuf.UpholdAccountPayload toUpholdAccountPayloadProto(boolean serializeForHash) {
        return resolveBuilder(getUpholdAccountPayloadBuilder(serializeForHash), serializeForHash).build();
    }

    private bisq.account.protobuf.UpholdAccountPayload.Builder getUpholdAccountPayloadBuilder(boolean serializeForHash) {
        return bisq.account.protobuf.UpholdAccountPayload.newBuilder()
                .addAllSelectedCurrencyCodes(selectedCurrencyCodes)
                .setHolderName(holderName)
                .setAccountId(accountId);
    }

    public static UpholdAccountPayload fromProto(AccountPayload proto) {
        var countryBasedAccountPayload = proto.getCountryBasedAccountPayload();
        var payload = countryBasedAccountPayload.getUpholdAccountPayload();
        return new UpholdAccountPayload(
                proto.getId(),
                countryBasedAccountPayload.getCountryCode(),
                payload.getSelectedCurrencyCodesList(),
                payload.getHolderName(),
                payload.getAccountId()
        );
    }

    @Override
    public FiatPaymentMethod getPaymentMethod() {
        return FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.UPHOLD);
    }

    @Override
    public String getAccountDataDisplayString() {
        return new AccountDataDisplayStringBuilder(
                Res.get("paymentAccounts.holderName"), holderName,
                Res.get("paymentAccounts.uphold.accountId"), accountId
        ).toString();
    }
}