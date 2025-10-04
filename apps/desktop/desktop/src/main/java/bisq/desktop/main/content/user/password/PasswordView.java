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

package bisq.desktop.main.content.user.password;

import bisq.desktop.common.view.View;
import bisq.desktop.components.controls.MaterialPasswordField;
import bisq.desktop.components.controls.validator.EqualTextsValidator;
import bisq.i18n.Res;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PasswordView extends View<VBox, PasswordModel, PasswordController> {
    private final MaterialPasswordField password, confirmedPassword;
    private final Button button;
    private final Label headline;

    public PasswordView(PasswordModel model, PasswordController controller) {
        super(new VBox(20), model, controller);

        root.setAlignment(Pos.TOP_LEFT);
        root.setPadding(new Insets(0, 40, 40, 40));

        headline = new Label();
        headline.getStyleClass().add("large-thin-headline");

        password = new MaterialPasswordField(Res.get("user.password.enterPassword"));
        password.setValidators(
                model.getPwdRequiredFieldValidator(),
                model.getPwdMinLengthValidator());

        confirmedPassword = new MaterialPasswordField(Res.get("user.password.confirmPassword"));
        confirmedPassword.setValidators(
                model.getConfirmedPwdRequiredFieldValidator(),
                model.getConfirmedPwdMinLengthValidator(),
                new EqualTextsValidator(Res.get("validation.password.notMatching"), password.getTextInputControl()));

        button = new Button();
        button.setDefaultButton(true);
        VBox.setMargin(password, new Insets(0, 0, -10, 0));
        root.getChildren().setAll(headline, password, confirmedPassword, button);
    }

    @Override
    protected void onViewAttached() {
        resetValidations();
        headline.textProperty().bind(model.getHeadline());
        password.passwordProperty().bindBidirectional(model.getPassword());
        password.isMaskedProperty().bindBidirectional(model.getPasswordIsMasked());
        password.isValidProperty().bindBidirectional(model.getPasswordIsValid());
        confirmedPassword.visibleProperty().bind(model.getConfirmedPasswordVisible());
        confirmedPassword.managedProperty().bind(model.getConfirmedPasswordVisible());
        confirmedPassword.passwordProperty().bindBidirectional(model.getConfirmedPassword());
        confirmedPassword.isMaskedProperty().bindBidirectional(model.getConfirmedPasswordIsMasked());
        confirmedPassword.isValidProperty().bindBidirectional(model.getConfirmedPasswordIsValid());
        button.textProperty().bind(model.getButtonText());
        button.setOnAction(e -> {
            password.validate();
            confirmedPassword.validate();
            controller.onButtonClicked();
        });
    }

    @Override
    protected void onViewDetached() {
        resetValidations();
        headline.textProperty().unbind();
        password.passwordProperty().unbindBidirectional(model.getPassword());
        password.isMaskedProperty().unbindBidirectional(model.getPasswordIsMasked());
        password.isValidProperty().unbindBidirectional(model.getPasswordIsValid());
        confirmedPassword.visibleProperty().unbind();
        confirmedPassword.managedProperty().unbind();
        confirmedPassword.passwordProperty().unbindBidirectional(model.getConfirmedPassword());
        confirmedPassword.isMaskedProperty().unbindBidirectional(model.getConfirmedPasswordIsMasked());
        confirmedPassword.isValidProperty().unbindBidirectional(model.getConfirmedPasswordIsValid());
        button.textProperty().unbind();
        button.setOnAction(null);
    }

    public void resetValidations() {
        password.resetValidation();
        confirmedPassword.resetValidation();
    }
}
