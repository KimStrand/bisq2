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

package bisq.trade;

import bisq.account.AccountService;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.release.AppType;
import bisq.burningman.BurningmanService;
import bisq.chat.ChatService;
import bisq.common.observable.Observable;
import bisq.common.observable.collection.ObservableSet;
import bisq.contract.ContractService;
import bisq.identity.IdentityService;
import bisq.network.NetworkService;
import bisq.offer.OfferService;
import bisq.persistence.PersistenceService;
import bisq.settings.SettingsService;
import bisq.support.SupportService;
import bisq.user.UserService;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceTest {
    private final NetworkService networkService = mock(NetworkService.class);
    private final TradeService service = createService();

    @Test
    void theApplicationStillStartsV1ByDefault() throws Exception {
        assertTrue(service.initializeMuSigTradeService().get(10, TimeUnit.SECONDS));

        try {
            verify(networkService).addConfidentialMessageListener(service.getMuSigTradeService());
            assertNotNull(service.getMuSigTradeService().getMusigGrpcClient().getBlockingStub());
        } finally {
            assertTrue(service.shutdownMuSigTradeService().get(10, TimeUnit.SECONDS));
        }
        verify(networkService).removeConfidentialMessageListener(service.getMuSigTradeService());
        assertNull(service.getMuSigTradeService().getMusigGrpcClient().getBlockingStub());
    }

    private TradeService createService() {
        var config = new TradeService.Config(ConfigFactory.parseString("grpcServer { host = localhost, port = 1 }"));
        var chat = mock(ChatService.class, RETURNS_DEEP_STUBS);
        when(chat.getMuSigOpenTradeChannelService().getChannels()).thenReturn(new ObservableSet<>());
        var roles = mock(BondedRolesService.class, RETURNS_DEEP_STUBS);
        when(roles.getAlertService().getAuthorizedAlertDataSet()).thenReturn(new ObservableSet<>());
        var settings = mock(SettingsService.class, RETURNS_DEEP_STUBS);
        when(settings.getNumDaysAfterRedactingTradeData()).thenReturn(new Observable<>(90));
        return new TradeService(config,
                networkService,
                mock(IdentityService.class),
                mock(PersistenceService.class, RETURNS_DEEP_STUBS),
                mock(OfferService.class),
                mock(ContractService.class),
                mock(SupportService.class),
                chat,
                roles,
                mock(UserService.class, RETURNS_DEEP_STUBS),
                settings,
                mock(AccountService.class),
                mock(BurningmanService.class),
                AppType.DESKTOP);
    }
}
