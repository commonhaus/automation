package org.commonhaus.automation.github.context;

import java.util.Date;

import jakarta.json.JsonObject;

public class DataTier extends DataCommonType {

    static final String TIER_FIELDS = """
            id
            name
            monthlyPriceInCents
            monthlyPriceInDollars
            isOneTime
            isCustomAmount
            """;

    public final Date createdAt;
    public final Integer monthlyPriceInCents;
    public final Integer monthlyPriceInDollars;
    public final String name;
    public final boolean isOneTime;
    public final boolean isCustomAmount;

    DataTier(JsonObject object) {
        super(object);
        this.createdAt = JsonAttribute.createdAt.dateFrom(object);
        this.monthlyPriceInCents = JsonAttribute.monthlyPriceInCents.integerFrom(object);
        this.monthlyPriceInDollars = JsonAttribute.monthlyPriceInDollars.integerFrom(object);
        this.name = JsonAttribute.name.stringFrom(object);
        this.isOneTime = JsonAttribute.isOneTime.booleanFromOrFalse(object);
        this.isCustomAmount = JsonAttribute.isCustomAmount.booleanFromOrFalse(object);
    }
}
