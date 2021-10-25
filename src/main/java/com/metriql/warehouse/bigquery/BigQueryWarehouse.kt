package com.metriql.warehouse.bigquery

import com.fasterxml.jackson.annotation.JsonAlias
import com.metriql.warehouse.spi.Warehouse

object BigQueryWarehouse : Warehouse<BigQueryWarehouse.BigQueryConfig> {
    override val names = Warehouse.Name("bigQuery", "bigquery")

    override val bridge = BigQueryMetriqlBridge

    override val configClass = BigQueryConfig::class.java

    override fun getDataSource(config: BigQueryConfig) = BigQueryDataSource(config)

    data class BigQueryConfig(
        val dataset: String,
        val project: String? = null,
        @JsonAlias("serviceAccountJSON")
        val keyfile_json: String? = null,
        @JsonAlias("maximum_bytes_billed")
        val maximumBytesBilled: Long? = null,
        @JsonAlias("timeout_seconds")
        val timeoutSeconds: Long? = null,
        val location: String? = null,
        val priority: String? = null,
        val retries: Int? = null,
        val keyFile: String? = null,
        val method: Method? = null,
        val refresh_token: String? = null,
        val client_id: String? = null,
        val client_secret: String? = null,
        val token_uri: String? = null,
    ) : Warehouse.Config {
        enum class Method {
            `service-account`, `oauth-secrets`, `service-account-json`, oauth
        }

        override fun toString(): String = "$dataset - $project"
        override fun stripPassword() = this.copy(keyfile_json = "", client_secret = null)
        override fun isValid() = true
        override fun warehouseSchema() = dataset
        override fun warehouseDatabase() = project
    }
}

class BigQueryWarehouseProxy : Warehouse<BigQueryWarehouse.BigQueryConfig> by BigQueryWarehouse
