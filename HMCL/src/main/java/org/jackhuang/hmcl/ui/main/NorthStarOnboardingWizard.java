/*
 * NorthStar Client, based on Hello Minecraft! Launcher
 * Copyright (C) 2026 NorthStar & the HMCL contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.main;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.account.MicrosoftAccountLoginPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.download.NorthStarClientInstaller;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jackhuang.hmcl.util.PortablePath;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// First-launch onboarding wizard that walks new players through the three steps needed before
/// playing on the NorthStar server: signing in, installing the client modpack, and joining.
///
/// Every step embeds its real action, so completing the wizard means the player has actually
/// configured the launcher rather than only clicked through it. Each step can be skipped.
///
/// The shown-once flag ([LauncherSettings northstarOnboardingFinished][org.jackhuang.hmcl.setting.LauncherSettings])
/// is set as soon as the wizard starts: closing it through any path - finishing, skipping, or
/// cancelling - should never bring it back on the next launch.
@NotNullByDefault
public final class NorthStarOnboardingWizard implements WizardProvider {

    @Override
    public void start(SettingsMap settings) {
        SettingsManager.settings().northstarOnboardingFinishedProperty().set(true);
    }

    @Override
    public @Nullable Object finish(SettingsMap settings) {
        return null;
    }

    @Override
    public Node createPage(WizardController controller, int step, SettingsMap settings) {
        return switch (step) {
            case 0 -> new WelcomePage(controller);
            case 1 -> new AccountPage(controller);
            case 2 -> new ModpackPage(controller);
            case 3 -> new FinishPage(controller);
            default -> throw new IllegalArgumentException("Unsupported onboarding step: " + step);
        };
    }

    @Override
    public boolean cancel() {
        return true;
    }

    /// Leaves the wizard and returns to the main page; used by action buttons that hand control
    /// over to another flow (the Microsoft login dialog or the modpack installer).
    private static void leaveWizard(WizardController controller) {
        controller.onEnd();
    }

    /// Joins the NorthStar server, or explains what is still missing when no instance exists.
    private static void joinServer() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        @Nullable HMCLGameInstance instance = repository.getSelectedInstance();
        if (instance != null) {
            Instances.launchAndJoinServer(instance, Metadata.NORTHSTAR_SERVER_ADDRESS);
        } else {
            Controllers.dialog(i18n("northstar.onboarding.launch.no_instance"),
                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
        }
    }

    private abstract static class OnboardingPage extends VBox implements WizardPage {
        final WizardController controller;

        OnboardingPage(WizardController controller, String titleKey, String contentKey) {
            this.controller = controller;

            setSpacing(12);
            setAlignment(Pos.TOP_LEFT);
            setPadding(new Insets(16));

            Label titleLabel = new Label(i18n(titleKey));
            titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
            titleLabel.setWrapText(true);

            Label contentLabel = new Label(i18n(contentKey));
            contentLabel.setWrapText(true);

            getChildren().setAll(titleLabel, contentLabel);
        }

        /// Adds a primary action button that also leaves the wizard before running [action].
        protected void addLeaveActionButton(String textKey, Runnable action) {
            var button = FXUtils.newRaisedButton(i18n(textKey));
            button.setOnAction(e -> {
                leaveWizard(controller);
                action.run();
            });
            getChildren().add(button);
        }

        /// Adds a button that stays in the wizard and only runs [action].
        protected void addStayActionButton(String textKey, Runnable action) {
            var button = FXUtils.newRaisedButton(i18n(textKey));
            button.setOnAction(e -> action.run());
            getChildren().add(button);
        }

        /// Adds a button advancing to the next wizard step.
        protected void addNextButton(String textKey) {
            var button = FXUtils.newRaisedButton(i18n(textKey));
            button.setOnAction(e -> controller.onNext(this));
            getChildren().add(button);
        }
    }

    private static final class WelcomePage extends OnboardingPage {
        WelcomePage(WizardController controller) {
            super(controller, "northstar.onboarding.welcome.title", "northstar.onboarding.welcome.content");

            PortablePath gameDirectory = GameDirectoryManager.getSelectedGameDirectory().getPath();
            Label directoryLabel = new Label(i18n("northstar.onboarding.welcome.directory", gameDirectory.getPath()));
            directoryLabel.setWrapText(true);
            directoryLabel.setStyle("-fx-opacity: 0.7;");
            getChildren().add(directoryLabel);

            addNextButton("northstar.onboarding.welcome.next");
        }

        @Override
        public String getTitle() {
            return i18n("northstar.onboarding.welcome.title");
        }
    }

    private static final class AccountPage extends OnboardingPage {
        AccountPage(WizardController controller) {
            super(controller, "northstar.onboarding.account.title", "northstar.onboarding.account.content");

            addLeaveActionButton("northstar.onboarding.account.microsoft",
                    () -> Controllers.dialog(new MicrosoftAccountLoginPane()));
            addStayActionButton("northstar.onboarding.account.offline",
                    () -> Controllers.dialog(new CreateAccountPane()));
            addNextButton("northstar.onboarding.skip");
        }

        @Override
        public String getTitle() {
            return i18n("northstar.onboarding.account.title");
        }
    }

    private static final class ModpackPage extends OnboardingPage {
        ModpackPage(WizardController controller) {
            super(controller, "northstar.onboarding.modpack.title", "northstar.onboarding.modpack.content");

            addLeaveActionButton("northstar.onboarding.modpack.install",
                    NorthStarClientInstaller::installLatest);
            addNextButton("northstar.onboarding.modpack.later");
        }

        @Override
        public String getTitle() {
            return i18n("northstar.onboarding.modpack.title");
        }
    }

    private static final class FinishPage extends OnboardingPage {
        FinishPage(WizardController controller) {
            super(controller, "northstar.onboarding.finish.title", "northstar.onboarding.finish.content");

            addLeaveActionButton("northstar.onboarding.finish.join",
                    NorthStarOnboardingWizard::joinServer);
            addLeaveActionButton("northstar.onboarding.finish.done", () -> {
            });
        }

        @Override
        public String getTitle() {
            return i18n("northstar.onboarding.finish.title");
        }
    }
}
