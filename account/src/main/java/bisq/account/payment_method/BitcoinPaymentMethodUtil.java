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

import java.util.List;
import java.util.stream.Collectors;

public class BitcoinPaymentMethodUtil {
    public static BitcoinPaymentMethod getPaymentMethod(String name) {
        try {
            BitcoinPaymentRail bitcoinPaymentRail = BitcoinPaymentRail.valueOf(name);
            BitcoinPaymentMethod bitcoinPaymentMethod = BitcoinPaymentMethod.fromPaymentRail(bitcoinPaymentRail);
            if (!bitcoinPaymentMethod.isCustomPaymentMethod()) {
                return bitcoinPaymentMethod;
            }
        } catch (Throwable ignore) {
        }
        return BitcoinPaymentMethod.fromCustomName(name);
    }

    public static List<BitcoinPaymentMethod> getAllPaymentMethods() {
        return getAllPaymentRails().stream()
                .map(BitcoinPaymentMethod::fromPaymentRail)
                .collect(Collectors.toList());
    }

    public static List<BitcoinPaymentRail> getAllPaymentRails() {
        return List.of(BitcoinPaymentRail.values());
    }
}