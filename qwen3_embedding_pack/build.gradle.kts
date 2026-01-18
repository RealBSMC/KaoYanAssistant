plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName = "qwen3_embedding_pack"
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}
