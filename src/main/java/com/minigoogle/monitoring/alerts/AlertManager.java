package com.minigoogle.monitoring.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Threshold-based alert manager.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Alert rules are evaluated periodically.
 *   When a metric crosses a threshold, an alert is triggered.
 */
public class AlertManager {

    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final List<AlertEvent> firedAlerts = new ArrayList<>();
    private final List<AlertListener> listeners = new ArrayList<>();

    /**
     * Registers an alert rule.
     */
    public void addRule(String metricName, AlertRule rule) {
        rules.put(metricName, rule);
    }

    /**
     * Evaluates all rules against a metric snapshot.
     * Triggers alerts for any violated thresholds.
     *
     * @param metrics Current metric values (name → value).
     */
    public void evaluate(Map<String, Double> metrics) {
        for (Map.Entry<String, AlertRule> entry : rules.entrySet()) {
            Double value = metrics.get(entry.getKey());
            if (value != null && entry.getKey() != null) {
                AlertRule rule = entry.getValue();
                if (rule.isViolated(value)) {
                    AlertEvent event = new AlertEvent(
                            entry.getKey(), value, rule,
                            rule.getSeverity(), System.currentTimeMillis());
                    firedAlerts.add(event);
                    for (AlertListener listener : listeners) {
                        listener.onAlert(event);
                    }
                }
            }
        }
    }

    /**
     * Returns all alerts that have been fired.
     */
    public List<AlertEvent> getFiredAlerts() {
        return List.copyOf(firedAlerts);
    }

    /**
     * Clears all fired alerts.
     */
    public void clearAlerts() {
        firedAlerts.clear();
    }

    /**
     * Registers an alert listener.
     */
    public void addListener(AlertListener listener) {
        listeners.add(listener);
    }

    public record AlertRule(String metricName, double threshold, String severity) {
        public boolean isViolated(double value) {
            return value > threshold;
        }
        public String getSeverity() { return severity; }
    }

    public record AlertEvent(String metricName, double currentValue, AlertRule rule,
                              String severity, long timestamp) {
    }

    public interface AlertListener {
        void onAlert(AlertEvent event);
    }
}
