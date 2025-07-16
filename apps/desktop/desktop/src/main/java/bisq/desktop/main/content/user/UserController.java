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

package bisq.desktop.main.content.user;

import bisq.desktop.ServiceProvider;
import bisq.desktop.common.view.Controller;
import bisq.desktop.main.content.ContentTabController;
import bisq.desktop.main.content.user.crypto_accounts.CryptoCurrencyAccountsController;
import bisq.desktop.main.content.user.fiat_accounts.FiatPaymentAccountsController;
import bisq.desktop.main.content.user.password.PasswordController;
import bisq.desktop.main.content.user.user_profile.UserProfileController;
import bisq.desktop.navigation.NavigationTarget;
import bisq.mu_sig.MuSigService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class UserController extends ContentTabController<UserModel> {
    @Getter
    private final UserView view;
    private final MuSigService muSigService;

    public UserController(ServiceProvider serviceProvider) {
        super(new UserModel(), NavigationTarget.USER, serviceProvider);
        muSigService = serviceProvider.getMuSigService();
        view = new UserView(model, this);


    }

    @Override
    public void onActivate() {
        super.onActivate();

        model.setShowCryptoPaymentAccounts(muSigService.getMuSigActivated().get());
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
    }

    protected Optional<? extends Controller> createController(NavigationTarget navigationTarget) {
        return switch (navigationTarget) {
            case USER_PROFILE -> Optional.of(new UserProfileController(serviceProvider));
            case PASSWORD -> Optional.of(new PasswordController(serviceProvider));
            case FIAT_PAYMENT_ACCOUNTS -> Optional.of(new FiatPaymentAccountsController(serviceProvider));
            case CRYPTO_CURRENCY_ACCOUNTS -> Optional.of(new CryptoCurrencyAccountsController(serviceProvider));
            default -> Optional.empty();
        };
    }
}
