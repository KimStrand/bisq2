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

package bisq.desktop.main.content.user.fiat_accounts.create.data;

import bisq.account.accounts.AccountPayload;
import bisq.account.payment_method.CryptoPaymentRail;
import bisq.account.payment_method.FiatPaymentRail;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentRail;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.view.Controller;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.F2FPaymentFormController;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.FasterPaymentsPaymentFormController;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.NationalBankPaymentFormController;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.PaymentFormController;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.PixPaymentFormController;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.RevolutPaymentFormController;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.SepaPaymentFormController;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.ZellePaymentFormController;
import javafx.scene.layout.VBox;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class PaymentDataController implements Controller {
    private final PaymentDataModel model;
    @Getter
    private final PaymentDataView view;
    private final ServiceProvider serviceProvider;
    private PaymentFormController<?, ?, ?> paymentFormController;

    public PaymentDataController(ServiceProvider serviceProvider) {
        this.serviceProvider = serviceProvider;

        model = new PaymentDataModel();
        view = new PaymentDataView(model, this);
    }

    public AccountPayload<?> getAccountPayload() {
        return paymentFormController.createAccountPayload();
    }

    public void setPaymentMethod(PaymentMethod<?> paymentMethod) {
        checkNotNull(paymentMethod, "PaymentMethod must not be null");
        model.setPaymentMethod(paymentMethod);
    }

    @Override
    public void onActivate() {
        PaymentMethod<?> paymentMethod = model.getPaymentMethod();
        checkNotNull(paymentMethod, "PaymentMethod must be set before onActivate is called");

        paymentFormController = getOrCreateController(paymentMethod);
        VBox root = paymentFormController.getView().getRoot();
        model.setPaymentForm(root);
    }

    @Override
    public void onDeactivate() {
        model.setPaymentForm(null);
    }

    public boolean validate() {
        return paymentFormController != null && paymentFormController.validate();
    }

    public PaymentFormController<?, ?, ?> getOrCreateController(PaymentMethod<?> paymentMethod) {
        String key = paymentMethod.getPaymentRail().name();
        return model.getControllerCache().computeIfAbsent(key, k -> createController(paymentMethod));
    }

    public PaymentFormController<?, ?, ?> createController(PaymentMethod<?> paymentMethod) {
        PaymentRail paymentRail = paymentMethod.getPaymentRail();
        if (paymentRail instanceof FiatPaymentRail fiatPaymentRail) {
            return getPaymentFormController(fiatPaymentRail);
        } else if (paymentRail instanceof CryptoPaymentRail cryptoPaymentRail) {
            {
                throw new UnsupportedOperationException("CryptoPaymentRail not implemented yet");
            }
        } else {
            throw new UnsupportedOperationException("No implementation found for " + paymentRail.name());
        }
    }

    private PaymentFormController<?, ?, ?> getPaymentFormController(PaymentRail paymentRail) {
        if (paymentRail instanceof FiatPaymentRail fiatPaymentRail) {
            return getFiatPaymentFormController(fiatPaymentRail);
        } else{
            throw new UnsupportedOperationException("PaymentRail not supported: " + paymentRail.name());
        }
    }


    private PaymentFormController<?, ?, ?> getFiatPaymentFormController(FiatPaymentRail fiatPaymentRail) {
        return switch (fiatPaymentRail) {
            case CUSTOM -> throw new UnsupportedOperationException("Not implemented yet");
            case SEPA -> new SepaPaymentFormController(serviceProvider);
            case SEPA_INSTANT -> throw new UnsupportedOperationException("Not implemented yet");
            case ZELLE -> new ZellePaymentFormController(serviceProvider);
            case REVOLUT -> new RevolutPaymentFormController(serviceProvider);
            case WISE -> throw new UnsupportedOperationException("Not implemented yet");
            case NATIONAL_BANK -> new NationalBankPaymentFormController(serviceProvider);
            case SAME_BANK -> throw new UnsupportedOperationException("Not implemented yet");
            case SWIFT -> throw new UnsupportedOperationException("Not implemented yet");
            case F2F -> new F2FPaymentFormController(serviceProvider);
            case WISE_USD -> throw new UnsupportedOperationException("Not implemented yet");
            case ACH_TRANSFER -> throw new UnsupportedOperationException("Not implemented yet");
            case PIX -> new PixPaymentFormController(serviceProvider);
            case HAL_CASH -> throw new UnsupportedOperationException("Not implemented yet");
            case PIN_4 -> throw new UnsupportedOperationException("Not implemented yet");
            case SWISH -> throw new UnsupportedOperationException("Not implemented yet");
            case FASTER_PAYMENTS -> new FasterPaymentsPaymentFormController(serviceProvider);
            case PAY_ID -> throw new UnsupportedOperationException("Not implemented yet");
            case US_POSTAL_MONEY_ORDER -> throw new UnsupportedOperationException("Not implemented yet");
            case CASH_BY_MAIL -> throw new UnsupportedOperationException("Not implemented yet");
            case STRIKE -> throw new UnsupportedOperationException("Not implemented yet");
            case INTERAC_E_TRANSFER -> throw new UnsupportedOperationException("Not implemented yet");
            case UPHOLD -> throw new UnsupportedOperationException("Not implemented yet");
            case AMAZON_GIFT_CARD -> throw new UnsupportedOperationException("Not implemented yet");
            case CASH_DEPOSIT -> throw new UnsupportedOperationException("Not implemented yet");
            case PROMPT_PAY -> throw new UnsupportedOperationException("Not implemented yet");
            case UPI -> throw new UnsupportedOperationException("Not implemented yet");
            case BIZUM -> throw new UnsupportedOperationException("Not implemented yet");
            case CASH_APP -> throw new UnsupportedOperationException("Not implemented yet");
            case DOMESTIC_WIRE_TRANSFER -> throw new UnsupportedOperationException("Not implemented yet");
            case MONEY_BEAM -> throw new UnsupportedOperationException("Not implemented yet");
            case MONEY_GRAM -> throw new UnsupportedOperationException("Not implemented yet");
        };
    }
}