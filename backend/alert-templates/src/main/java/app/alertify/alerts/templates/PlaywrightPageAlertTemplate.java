package app.alertify.alerts.templates;

import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.playwright.PlaywrightPageEngine;

@AlertTemplate(
    nameKey = "alerts.template.playwrightPage.name",
    descriptionKey = "alerts.template.playwrightPage.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.network", color = "#0EA5E9"),
    capability = WorkerCapability.PLAYWRIGHT,
    sourcePath = "app/alertify/alerts/templates/PlaywrightPageAlertTemplate.java"
)
public final class PlaywrightPageAlertTemplate extends PlaywrightPageEngine {
    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.url",
        descriptionKey = "alerts.template.playwrightPage.urlDescription",
        order = 1
    )
    private final String url;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.chromiumEnabled",
        descriptionKey = "alerts.template.playwrightPage.chromiumEnabledDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 2
    )
    private final boolean chromiumEnabled;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.firefoxEnabled",
        descriptionKey = "alerts.template.playwrightPage.firefoxEnabledDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "false",
        order = 3
    )
    private final boolean firefoxEnabled;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.webkitEnabled",
        descriptionKey = "alerts.template.playwrightPage.webkitEnabledDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "false",
        order = 4
    )
    private final boolean webkitEnabled;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.loadTimeoutSeconds",
        descriptionKey = "alerts.template.playwrightPage.loadTimeoutSecondsDescription",
        options = { "5", "10", "15", "30", "60" },
        defaultValue = "10",
        order = 5
    )
    private final int loadTimeoutSeconds;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.elementTimeoutSeconds",
        descriptionKey = "alerts.template.playwrightPage.elementTimeoutSecondsDescription",
        options = { "1", "3", "5", "10", "30" },
        defaultValue = "5",
        order = 6
    )
    private final int elementTimeoutSeconds;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.steps",
        descriptionKey = "alerts.template.playwrightPage.stepsDescription",
        multiline = true,
        order = 7
    )
    private final String steps;

    public PlaywrightPageAlertTemplate(String url, boolean chromiumEnabled, boolean firefoxEnabled, boolean webkitEnabled, int loadTimeoutSeconds, int elementTimeoutSeconds, String steps) {
        this(url, chromiumEnabled, firefoxEnabled, webkitEnabled, loadTimeoutSeconds, elementTimeoutSeconds, steps, PlaywrightBrowserSession::open);
    }

    PlaywrightPageAlertTemplate(String url, boolean chromiumEnabled, boolean firefoxEnabled, boolean webkitEnabled, int loadTimeoutSeconds, int elementTimeoutSeconds, String steps, BrowserSessionFactory sessionFactory) {
        super(url, chromiumEnabled, firefoxEnabled, webkitEnabled, loadTimeoutSeconds, elementTimeoutSeconds, steps, sessionFactory);
        this.url = url;
        this.chromiumEnabled = chromiumEnabled;
        this.firefoxEnabled = firefoxEnabled;
        this.webkitEnabled = webkitEnabled;
        this.loadTimeoutSeconds = loadTimeoutSeconds;
        this.elementTimeoutSeconds = elementTimeoutSeconds;
        this.steps = steps;
    }
}
