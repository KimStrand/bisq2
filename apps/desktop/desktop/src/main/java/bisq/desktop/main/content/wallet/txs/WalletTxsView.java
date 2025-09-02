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

package bisq.desktop.main.content.wallet.txs;

import bisq.desktop.common.view.View;
import bisq.desktop.components.table.BisqTableColumn;
import bisq.desktop.components.table.RichTableView;
import bisq.i18n.Res;
import javafx.geometry.Insets;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;

@Slf4j
public class WalletTxsView extends View<VBox, WalletTxsModel, WalletTxsController> {
    private final RichTableView<WalletTransactionListItem> richTableView;

    public WalletTxsView(WalletTxsModel model, WalletTxsController controller) {
        super(new VBox(20), model, controller);

        root.setPadding(new Insets(0, 40, 40, 40));

        richTableView = new RichTableView<>(
                model.getSortedList(),
                Res.get("wallet.txs"),
                controller::applySearchPredicate);
        richTableView.setMinHeight(300);
        // Triggers to fill the available height
        richTableView.setPrefHeight(2000);
        configTableView();

        root.getChildren().add(richTableView);
    }

    @Override
    protected void onViewAttached() {
        richTableView.initialize();
    }

    @Override
    protected void onViewDetached() {
        richTableView.dispose();
    }

    private void configTableView() {
        BisqTableColumn<WalletTransactionListItem> dateColumn = new BisqTableColumn.Builder<WalletTransactionListItem>()
                .title(Res.get("wallet.txs.date"))
                .left()
                .minWidth(80)
                .comparator(Comparator.comparing(WalletTransactionListItem::getDateTimeString))
                .valueSupplier(WalletTransactionListItem::getDateTimeString)
                .sortType(TableColumn.SortType.DESCENDING)
                .build();
        richTableView.getColumns().add(dateColumn);
        richTableView.getSortOrder().add(dateColumn);

        richTableView.getColumns().add(new BisqTableColumn.Builder<WalletTransactionListItem>()
                .title(Res.get("wallet.txs.trade"))
                .minWidth(60)
                .left()
                .valueSupplier(WalletTransactionListItem::getTrade)
                .isSortable(true)
                .build());

        richTableView.getColumns().add(new BisqTableColumn.Builder<WalletTransactionListItem>()
                .title(Res.get("wallet.txs.type"))
                .minWidth(70)
                .left()
                .valueSupplier(WalletTransactionListItem::getType)
                .isSortable(true)
                .build());

        richTableView.getColumns().add(new BisqTableColumn.Builder<WalletTransactionListItem>()
                .title(Res.get("wallet.txs.address"))
                .minWidth(180)
                .left()
                .valueSupplier(WalletTransactionListItem::getDestinationAddress)
                .isSortable(true)
                .build());

        richTableView.getColumns().add(new BisqTableColumn.Builder<WalletTransactionListItem>()
                .title(Res.get("wallet.txs.txId"))
                .minWidth(200)
                .left()
                .valueSupplier(WalletTransactionListItem::getTxId)
                .isSortable(true)
                .build());

        richTableView.getColumns().add(new BisqTableColumn.Builder<WalletTransactionListItem>()
                .title(Res.get("wallet.txs.amount"))
                .minWidth(120)
                .valueSupplier(WalletTransactionListItem::getAmountAsString)
                .comparator(Comparator.comparing(WalletTransactionListItem::getAmount))
                .sortType(TableColumn.SortType.DESCENDING)
                .build());

        richTableView.getColumns().add(new BisqTableColumn.Builder<WalletTransactionListItem>()
                .title(Res.get("wallet.txs.confirmations"))
                .minWidth(120)
                .valueSupplier(WalletTransactionListItem::getNumConfirmationsAsString)
                .comparator(Comparator.comparing(WalletTransactionListItem::getNumConfirmations))
                .sortType(TableColumn.SortType.DESCENDING)
                .right()
                .build());
    }
}
