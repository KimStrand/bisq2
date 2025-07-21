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

package bisq.desktop.components.controls;

import bisq.common.locale.LocaleRepository;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import lombok.Getter;

import java.text.DecimalFormatSymbols;
import java.util.regex.Pattern;

public class BitcoinAmountDisplay extends HBox {
    @Getter
    private final StringProperty btcAmount = new SimpleStringProperty("");
    private final TextFlow valueTextFlow = new TextFlow();
    @Getter
    private final Text integerPart = new Text();
    @Getter
    private final Text leadingZeros = new Text();
    @Getter
    private final Text significantDigits = new Text();
    @Getter
    private final Text btcCode = new Text(" BTC");
    @SuppressWarnings("FieldCanBeLocal")
    private final ChangeListener<String> amountChangeListener =
            (obs, old, newVal) -> updateDisplay();

    public BitcoinAmountDisplay() {
        this("0");
    }

    public BitcoinAmountDisplay(String amount, boolean showBtcCode) {
        this(amount);
        btcCode.setVisible(showBtcCode);
        btcCode.setManaged(showBtcCode);
    }

    public BitcoinAmountDisplay(String amount) {
        setAlignment(Pos.CENTER);

        valueTextFlow.setTextAlignment(TextAlignment.CENTER);
        getChildren().add(valueTextFlow);

        integerPart.getStyleClass().add("bitcoin-amount-display-integer-part");
        leadingZeros.getStyleClass().add("bitcoin-amount-display-leading-zeros-empty");
        significantDigits.getStyleClass().add("bitcoin-amount-display-significant-digits");
        btcCode.getStyleClass().add("bitcoin-amount-display-code");

        valueTextFlow.getChildren().addAll(integerPart, leadingZeros, significantDigits, btcCode);

        btcAmount.set(amount);

        btcAmount.addListener(new WeakChangeListener<>(amountChangeListener));

        getStyleClass().add("bitcoin-amount-display-text");

        updateDisplay();
    }

    public void setBaselineAlignment() {
        setAlignment(Pos.CENTER);
        setSpacing(0);
        valueTextFlow.setLineSpacing(0);
    }

    public void setTextAlignment(TextAlignment alignment) {
        valueTextFlow.setTextAlignment(alignment);
    }

    public void setFixedHeight(double height) {
        setMinHeight(height);
        setMaxHeight(height);
    }

    public void setPaddings(Insets padding) {
        setPadding(padding);
        valueTextFlow.setPadding(new Insets(0));
    }

    public void setBtcAmount(String amount) {
        btcAmount.set(amount);
    }

    public void setFontSize(double fontSize) {
        integerPart.setFont(new Font(integerPart.getFont().getName(), fontSize));
        leadingZeros.setFont(new Font(leadingZeros.getFont().getName(), fontSize));
        significantDigits.setFont(new Font(significantDigits.getFont().getName(), fontSize));
    }

    public void setBtcCodeFontSize(double fontSize) {
        Font btcCodeFont = btcCode.getFont();
        btcCode.setFont(new Font(btcCodeFont.getName(), fontSize));
    }

    public void applyCompactConfig(double mainFontSize, double btcCodeFontSize, double height) {
        setFontSize(mainFontSize);
        setBtcCodeFontSize(btcCodeFontSize);
        setBaselineAlignment();
        setFixedHeight(height);
        setPaddings(new Insets(0));
        setSpacing(0);
    }

    public void applySmallCompactConfig() {
        applyCompactConfig(18, 13, 28);
    }

    public void applyMediumCompactConfig() {
        applyCompactConfig(21, 18, 28);
    }

    public void applyMicroCompactConfig() {
        applyCompactConfig(12, 12, 24);
    }

    private void updateDisplay() {
        String amount = btcAmount.get();
        if (amount == null || amount.isEmpty()) {
            valueTextFlow.setVisible(false);
            return;
        }

        valueTextFlow.setVisible(true);
        formatBtcAmount(amount);
    }

    private void setExclusiveStyle(Text textNode, String styleToAdd, String styleToRemove) {
        textNode.getStyleClass().remove(styleToRemove);
        if (!textNode.getStyleClass().contains(styleToAdd)) {
            textNode.getStyleClass().add(styleToAdd);
        }
    }

    private void formatBtcAmount(String amount) {
        char decimalSeparator = DecimalFormatSymbols.getInstance(LocaleRepository.getDefaultLocale()).getDecimalSeparator();

        // If the amount does not contain a decimal separator, append it with eight zeros
        if (!amount.contains(String.valueOf(decimalSeparator))) {
            amount = amount + decimalSeparator + "00000000";
        }

        // Split the amount into integer and fractional parts
        String[] parts = amount.split(Pattern.quote(String.valueOf(decimalSeparator)));

        // Extract the integer part, defaulting to "0" if empty
        String integerPartValue = parts.length > 0 ? parts[0] : "";
        if (integerPartValue.isEmpty()) {
            integerPartValue = "0";
        }

        // Extract the fractional part, ensuring it is exactly 8 characters long
        String fractionalPart = parts.length > 1 ? parts[1] : "";
        if (fractionalPart.length() < 8) {
            fractionalPart = fractionalPart + "0".repeat(8 - fractionalPart.length());
        } else if (fractionalPart.length() > 8) {
            fractionalPart = fractionalPart.substring(0, 8);
        }

        // Reverse the fractional part to facilitate chunking
        StringBuilder reversedFractional = new StringBuilder(fractionalPart).reverse();

        // Create a chunked version of the reversed fractional part with spaces every 3 characters
        StringBuilder chunkedReversed = new StringBuilder();
        for (int i = 0; i < reversedFractional.length(); i++) {
            chunkedReversed.append(reversedFractional.charAt(i));
            if ((i + 1) % 3 == 0 && i < reversedFractional.length() - 1) {
                chunkedReversed.append(' ');
            }
        }

        // Reverse the chunked string back to its original order
        String formattedFractional = chunkedReversed.reverse().toString();

        // Extract leading zeros from the formatted fractional part
        StringBuilder leadingZerosValue = new StringBuilder();
        int integerValue = Integer.parseInt(integerPartValue);
        int i = 0;
        if (integerValue == 0) {
            while (i < formattedFractional.length() &&
                    (formattedFractional.charAt(i) == '0' || formattedFractional.charAt(i) == ' ')) {
                leadingZerosValue.append(formattedFractional.charAt(i));
                i++;
            }
        }

        // Extract the significant digits from the formatted fractional part
        String significantDigitsValue = formattedFractional.substring(i);

        // Set styles for the integer part based on its value
        if (integerValue > 0) {
            setExclusiveStyle(integerPart, "bitcoin-amount-display-integer-part", "bitcoin-amount-display-integer-part-dimmed");
        } else {
            setExclusiveStyle(integerPart, "bitcoin-amount-display-integer-part-dimmed", "bitcoin-amount-display-integer-part");
        }
        integerPart.setText(integerPartValue + decimalSeparator);

        // Set the text and styles for the leading zeros part
        leadingZeros.setText(leadingZerosValue.toString());
        if (leadingZerosValue.isEmpty()) {
            setExclusiveStyle(leadingZeros, "bitcoin-amount-display-leading-zeros-empty", "bitcoin-amount-display-leading-zeros-dimmed");
        } else {
            setExclusiveStyle(leadingZeros, "bitcoin-amount-display-leading-zeros-dimmed", "bitcoin-amount-display-leading-zeros-empty");
        }

        // Set the text for the significant digits part
        significantDigits.setText(significantDigitsValue);
    }
}