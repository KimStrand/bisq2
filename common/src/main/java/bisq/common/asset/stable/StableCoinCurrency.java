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

package bisq.common.asset.stable;


import bisq.common.asset.Asset;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class StableCoinCurrency extends Asset {
    private final String pegCurrencyCode;
    private final String chain;//TODO use StableCoinChain
    private final String standard; //TODO use StableCoinTokenStandard

    // We consider issues as only informational data, if for instance the backing company gets sold, the coin
    // should not change.
    @EqualsAndHashCode.Exclude
    private final String issuer; //TODO use StableCoinIssuer

    public StableCoinCurrency(String code,
                              String name,
                              String pegCurrencyCode,
                              StableCoinChain chain,
                              StableCoinTokenStandard standard,
                              StableCoinIssuer issuer) {
        this(code, name, pegCurrencyCode, chain.getDisplayName(), standard.getDisplayName(), issuer.getDisplayName());
    }

    public StableCoinCurrency(String code,
                              String name,
                              String pegCurrencyCode,
                              String chain,
                              String standard,
                              String issuer) {
        super(code, name);
        this.pegCurrencyCode = pegCurrencyCode;
        this.chain = chain;
        this.standard = standard;
        this.issuer = issuer;
    }

    public static boolean isStableCoinCurrency(String code) {
        return !StableCoinCurrencyRepository.allWithCode(code).isEmpty();
    }

    //todo
    @Override
    public bisq.common.protobuf.TradeCurrency.Builder getBuilder(boolean serializeForHash) {
        return getTradeCurrencyBuilder().setStableCoinCurrency(
                bisq.common.protobuf.StableCoinCurrency.newBuilder()
                        .setPegCurrencyCode(pegCurrencyCode)
                        .setChain(chain)
                        .setStandard(standard)
                        .setIssuer(issuer));
    }

    @Override
    public bisq.common.protobuf.TradeCurrency toProto(boolean serializeForHash) {
        return resolveProto(serializeForHash);
    }

    public static StableCoinCurrency fromProto(bisq.common.protobuf.TradeCurrency baseProto) {
        bisq.common.protobuf.StableCoinCurrency stableCoinCurrencyProto = baseProto.getStableCoinCurrency();
        return new StableCoinCurrency(baseProto.getCode(), baseProto.getName(),
                stableCoinCurrencyProto.getPegCurrencyCode(),
                stableCoinCurrencyProto.getChain(),
                stableCoinCurrencyProto.getStandard(),
                stableCoinCurrencyProto.getIssuer());
    }

    @Override
    public String getDisplayName() {
        // E.g. Tether USD (USDT, Ethereum ERC-20)
        return name + " (" + code + ", " + chain + " " + standard + ")";
    }

    public String getShortDisplayName() {
        // E.g. USDT (Ethereum ERC-20)
        return code + " (" + chain + " " + standard + ")";
    }
}
