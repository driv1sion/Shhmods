package com.shhmods.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantPolicyDto {

    private String ruleName;
    private String overrideAction;
    
    @JsonProperty("min_spam_trust")
    private Double minSpamTrust;
    
    @JsonProperty("min_account_age_days")
    private Double minAccountAgeDays;

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getOverrideAction() { return overrideAction; }
    public void setOverrideAction(String overrideAction) { this.overrideAction = overrideAction; }

    public Double getMinSpamTrust() { return minSpamTrust; }
    public void setMinSpamTrust(Double minSpamTrust) { this.minSpamTrust = minSpamTrust; }

    public Double getMinAccountAgeDays() { return minAccountAgeDays; }
    public void setMinAccountAgeDays(Double minAccountAgeDays) { this.minAccountAgeDays = minAccountAgeDays; }
}
