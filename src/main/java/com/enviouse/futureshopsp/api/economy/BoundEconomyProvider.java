package com.enviouse.futureshopsp.api.economy;

/** Optional provider extension for account bound transaction execution. */
public interface BoundEconomyProvider {
    ProviderResult<BoundEconomyOperationV1> bind(OperationRequest request, RequiredCapabilities required);

    ProviderResult<BalanceSnapshot> precheck(BoundEconomyOperationV1 operation);

    ProviderResult<MutationReceipt> mutate(BoundEconomyOperationV1 operation, MutationRequest request);

    ProviderResult<MutationReceipt> lookup(BoundEconomyOperationV1 operation);

    ProviderResult<MutationReceipt> retry(BoundEconomyOperationV1 operation);
}
