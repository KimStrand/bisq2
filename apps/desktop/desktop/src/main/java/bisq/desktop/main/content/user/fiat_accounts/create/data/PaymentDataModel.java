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

import bisq.account.payment_method.PaymentMethod;
import bisq.desktop.common.view.Model;
import bisq.desktop.main.content.user.fiat_accounts.create.data.payment_form.PaymentFormController;
import javafx.scene.layout.Region;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
@Getter
public class PaymentDataModel implements Model {
    @Setter
    private PaymentMethod<?> paymentMethod;
    @Setter
    private Region paymentForm;
    private final Map<String, PaymentFormController<?, ?, ?>> controllerCache = new HashMap<>();
}