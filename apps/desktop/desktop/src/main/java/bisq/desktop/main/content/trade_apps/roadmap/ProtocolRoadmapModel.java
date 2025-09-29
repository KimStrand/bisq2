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

package bisq.desktop.main.content.trade_apps.roadmap;

import bisq.account.protocol_type.TradeProtocolType;
import bisq.common.util.StringUtils;
import bisq.desktop.common.view.Model;
import lombok.Getter;

import java.util.Locale;

@Getter
public class ProtocolRoadmapModel implements Model {
    private final TradeProtocolType tradeProtocolType;
    private final String iconId;
    private final String url;

    public ProtocolRoadmapModel(TradeProtocolType tradeProtocolType, String url) {
        this.tradeProtocolType = tradeProtocolType;
        this.url = url;
        this.iconId = "protocol-" + StringUtils.snakeCaseToKebapCase(
                tradeProtocolType.name().toLowerCase(Locale.ROOT), Locale.ROOT);
    }
}
