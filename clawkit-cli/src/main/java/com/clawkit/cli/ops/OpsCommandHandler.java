package com.clawkit.cli.ops;

import com.clawkit.cli.ConsoleRenderer;
import com.clawkit.cli.remote.RemoteTargetStore;
import com.clawkit.ops.delivery.IncidentSummary;
import com.clawkit.ops.delivery.InvestigationRequest;
import com.clawkit.ops.delivery.InvestigationView;
import com.clawkit.ops.delivery.OpsInvestigationFacade;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code /ops} slash commands using deterministic parsing.
 *
 * <p>OPS-PRODUCT-LOOP-1 §12.
 */
public class OpsCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(OpsCommandHandler.class);

    private final OpsInvestigationFacade facade;
    private final RemoteTargetStore targetStore;
    private final JLineInvestigationInteraction interaction;

    public OpsCommandHandler(OpsInvestigationFacade facade, RemoteTargetStore targetStore,
                              JLineInvestigationInteraction interaction) {
        this.facade = facade;
        this.targetStore = targetStore;
        this.interaction = interaction;
    }

    /** Handle a /ops command. Returns true if recognized. */
    public boolean handle(String arguments) {
        var cmd = OpsCommandParser.parse(arguments);
        try {
            return switch (cmd.subCommand()) {
                case INVESTIGATE -> { cmdInvestigate(cmd); yield true; }
                case RECENT -> { cmdRecent(cmd.recentLimit()); yield true; }
                case INSPECT -> { cmdInspect(cmd.incidentId()); yield true; }
                case CONTINUE -> { cmdContinue(cmd.incidentId()); yield true; }
                case HELP -> { cmdHelp(); yield true; }
                case UNKNOWN -> { cmdHelp(); yield true; }
            };
        } catch (Exception e) {
            println("  [ERROR] " + e.getMessage());
            log.warn("[ops-cmd] failed: {}", e.getMessage());
            return true;
        }
    }

    private void cmdInvestigate(OpsCommandParser.ParsedCommand cmd) {
        String targetId = cmd.targetId();
        String serviceId = cmd.serviceId();

        if (targetId == null || targetId.isEmpty()) {
            println("  请指定要调查的目标服务器。");
            println("  用法: /ops investigate <targetId> [serviceId] [问题描述]");
            println("  已登记的目标: " + String.join(", ", targetStore.list()));
            return;
        }

        if (!targetStore.exists(targetId)) {
            println("  未找到目标: " + targetId);
            List<String> registered = targetStore.list();
            if (!registered.isEmpty()) {
                println("  已登记的目标: " + String.join(", ", registered));
            }
            println("  使用 /remote add --from-ssh <alias> 注册新目标");
            return;
        }

        if (!OpsCommandParser.isAllowedService(serviceId)) {
            println("  不支持的服务: " + serviceId);
            println("  当前仅支持: order-api");
            return;
        }

        println("  开始调查 " + targetId + " 上的 " + serviceId + "……\n");

        var request = new InvestigationRequest(targetId, serviceId, cmd.question());

        facade.investigateAndMaybeRepair(request, interaction);
    }

    private void cmdRecent(int limit) {
        List<IncidentSummary> incidents = facade.recent(limit);
        if (incidents.isEmpty()) {
            println("  暂无调查记录。");
            println("  使用 /ops investigate <target> <service> 开始调查。");
            return;
        }
        System.out.println();
        for (IncidentSummary s : incidents) {
            String time = s.updatedAt() != null
                ? s.updatedAt().toString().replace("T", " ").substring(0, 16) : "";
            String target = s.targetId() != null ? s.targetId() : "?";
            String svc = s.serviceId() != null ? " · " + s.serviceId() : "";
            println("  [" + s.status() + "] " + s.incidentId() + "  " + target + svc + "  " + time);
            if (s.briefSummary() != null && !s.briefSummary().isBlank()) {
                println("    " + s.briefSummary());
            }
        }
        System.out.println();
    }

    private void cmdInspect(String incidentId) {
        if (incidentId == null || incidentId.isEmpty()) {
            println("  用法: /ops inspect <incidentId>");
            println("  使用 /ops recent 查看最近的调查记录。");
            return;
        }

        InvestigationView view = facade.inspect(incidentId);
        if (view == null) {
            println("  未找到调查记录: " + incidentId);
            return;
        }

        System.out.println();
        println("  Incident:    " + view.incidentId());
        println("  Target:      " + view.targetId());
        if (view.serviceId() != null && !view.serviceId().isEmpty()) {
            println("  Service:     " + view.serviceId());
        }
        println("  Status:      " + view.status());
        println("  Created:     " + formatTime(view.createdAt()));
        println("  Updated:     " + formatTime(view.updatedAt()));

        if (view.diagnosis() != null && !view.diagnosis().isBlank()
            && !"未完成诊断".equals(view.diagnosis())) {
            println("  Diagnosis:   " + view.diagnosis());
        }
        if (view.recommendation() != null && !view.recommendation().isBlank()) {
            println("  Recommend:   " + view.recommendation());
        }
        if (view.actionExecuted() != null && !view.actionExecuted().isBlank()) {
            println("  Action:      " + view.actionExecuted());
        }
        if (view.verificationSummary() != null && !view.verificationSummary().isBlank()) {
            println("  Verify:      " + view.verificationSummary());
        }
        if (view.nextAction() != null && !view.nextAction().isBlank()) {
            println("  Next:        " + view.nextAction());
        }
        System.out.println();
        println("  Evidence:    ~/.clawkit/" + view.evidenceDirectory());
        System.out.println();
    }

    private void cmdContinue(String incidentId) {
        if (incidentId == null || incidentId.isEmpty()) {
            println("  用法: /ops continue <incidentId>");
            println("  使用 /ops recent 查看可继续的调查记录。");
            return;
        }

        println("  继续调查 " + incidentId + "……");
        facade.continueIncident(incidentId, interaction);
    }

    private void cmdHelp() {
        println("  /ops investigate <target> [service] [question]");
        println("      调查目标服务器上的服务状态");
        println("  /ops recent [limit]");
        println("      查看最近的调查记录（默认 10 条）");
        println("  /ops inspect <incidentId>");
        println("      查看调查详情");
        println("  /ops continue <incidentId>");
        println("      继续之前的调查");
        println("");
        println("  Quick start:");
        println("    1. /remote connect <target>");
        println("    2. /ops investigate <target> order-api");
        println("    3. /ops recent");
        println("    4. /ops inspect <incidentId>");
        println("");
        println("  仅支持已登记的目标和 order-api 服务。");
        println("  调查过程先执行只读操作；如需修复会请求审批。");
    }

    private static String formatTime(java.time.Instant instant) {
        if (instant == null) return "";
        return instant.toString().replace("T", " ").substring(0, 19);
    }

    private static void println(String text) {
        System.out.println(ConsoleRenderer.GRAY + text + ConsoleRenderer.RESET);
    }
}
