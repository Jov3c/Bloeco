package com.blocke.centraleconomy.economy;

/** Tax and fee controls exposed to authorised Bloeco tax administrators. */
public enum TaxType {
    PROCUREMENT_INCOME("收购所得税", "procurement-income-tax-rate"),
    TRANSFER_FEE("转账手续费", "transfer-fee-rate"),
    TRANSFER_INCOME("转账个人所得税", "transfer-income-tax-rate"),
    MARKET_CONSUMPTION("市场消费税", "market-consumption-tax-rate");

    private final String displayName;
    private final String configPath;

    TaxType(String displayName, String configPath) {
        this.displayName = displayName;
        this.configPath = configPath;
    }

    public String displayName() {
        return displayName;
    }

    public String configPath() {
        return configPath;
    }
}
