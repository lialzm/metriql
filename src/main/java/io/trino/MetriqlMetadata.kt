package io.trino

import com.fasterxml.jackson.core.type.TypeReference
import com.google.common.collect.ImmutableList
import com.metriql.db.FieldType
import com.metriql.dbt.DbtJinjaRenderer
import com.metriql.service.auth.ProjectAuth
import com.metriql.service.dataset.Dataset
import com.metriql.service.dataset.IDatasetService
import com.metriql.service.jdbc.extractModelNameFromPropertiesTable
import com.metriql.util.JsonHelper
import com.metriql.util.MetriqlException
import com.metriql.util.toSnakeCase
import io.netty.handler.codec.http.HttpResponseStatus
import io.trino.connector.system.SystemTablesProvider
import io.trino.spi.connector.ColumnMetadata
import io.trino.spi.connector.ConnectorPageSource
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.ConnectorTableMetadata
import io.trino.spi.connector.ConnectorTransactionHandle
import io.trino.spi.connector.InMemoryRecordSet
import io.trino.spi.connector.RecordCursor
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.connector.SystemTable
import io.trino.spi.predicate.TupleDomain
import io.trino.spi.type.ArrayType
import io.trino.spi.type.BigintType
import io.trino.spi.type.BooleanType
import io.trino.spi.type.DateType
import io.trino.spi.type.DecimalType
import io.trino.spi.type.DoubleType
import io.trino.spi.type.IntegerType
import io.trino.spi.type.TimeType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType
import io.trino.type.UnknownType
import java.util.Optional

class MetriqlMetadata(private val datasetService: IDatasetService) : SystemTablesProvider {

    private fun getModels(session: ConnectorSession): List<Dataset> {
        var metriql_auth=session.identity.extraCredentials["metriql"]
        if (metriql_auth==null){
            return datasetService.list(ProjectAuth.systemUser("", session.user)).filter { !it.hidden }
        }else{
            val auth = JsonHelper.read(session.identity.extraCredentials["metriql"], ProjectAuth::class.java)
            return datasetService.list(auth).filter { !it.hidden }
        }

    }

    override fun listSystemTables(session: ConnectorSession): Set<SystemTable> {
        val models = getModels(session)
        return models.map { ModelProxy(models, it) }.toSet()
    }

    override fun getSystemTable(session: ConnectorSession, tableName: SchemaTableName): Optional<SystemTable> {
        val models = getModels(session)
        val modelCategory = getCategoryFromSchema(tableName.schemaName)
        val propertiesForModel = extractModelNameFromPropertiesTable(tableName.tableName)
        val regexName = DbtJinjaRenderer.renderer.renderModelNameRegex(propertiesForModel ?: tableName.tableName).toRegex()
        val model = models.find { it.category?.lowercase() == modelCategory && it.name.matches(regexName) } ?: return Optional.empty()

        return if (propertiesForModel != null) {
            Optional.of(TablePropertiesTable(model))
        } else {
            Optional.of(ModelProxy(models, model))
        }
    }

    private fun getCategoryFromSchema(schemaName: String): Any? {
        return if (schemaName == "public") null else schemaName
    }

    class TablePropertiesTable(val dataset: Dataset) : SystemTable {
        private val metadata = ConnectorTableMetadata(
            SchemaTableName(dataset.category ?: "public", dataset.name),
            ImmutableList.of(
                ColumnMetadata("comment", VarcharType.VARCHAR)
            )
        )

        override fun getDistribution() = SystemTable.Distribution.SINGLE_COORDINATOR

        override fun getTableMetadata(): ConnectorTableMetadata {
            return metadata
        }

        override fun cursor(transactionHandle: ConnectorTransactionHandle, session: ConnectorSession, constraint: TupleDomain<Int>): RecordCursor {
            val table = InMemoryRecordSet.builder(metadata)
            table.addRow(dataset.description)
            return table.build().cursor()
        }
    }

    class ModelProxy(val datasets: List<Dataset>, val dataset: Dataset, val distinct: Boolean = false) : SystemTable {

        override fun pageSource(transactionHandle: ConnectorTransactionHandle, session: ConnectorSession, constraint: TupleDomain<Int>): ConnectorPageSource {
            throw UnsupportedOperationException("Metadata queries doesn't support data processing")
        }

        override fun getDistribution() = SystemTable.Distribution.SINGLE_COORDINATOR

        private fun toColumnMetadata(it: Dataset.Measure, relation: String? = null): ColumnMetadata? {
            return ColumnMetadata.builder()
                .setName(relation?.let { _ -> "$relation.${it.name}" } ?: it.name)
                .setComment(Optional.ofNullable(it.description))
                .setHidden(it.hidden ?: false)
                .setExtraInfo(Optional.ofNullable("measure"))
                .setNullable(true)
                .setProperties(JsonHelper.convert(it, object : TypeReference<Map<String, Any>>() {}))
                .setType(getTrinoType(it.fieldType))
                .build()
        }

        private fun toColumnMetadata(it: Dataset.Dimension, relation: String? = null, timeframe: String? = null): ColumnMetadata {
            val relationPrefix = relation?.let { "$it." } ?: ""
            val timeframeSuffix = timeframe?.let { "::${toSnakeCase(it)}" } ?: ""
            return ColumnMetadata.builder()
                .setName(relationPrefix + it.name + timeframeSuffix)
                .setComment(Optional.ofNullable(it.description))
                .setHidden(it.hidden ?: false)
                .setNullable(true)
                .setExtraInfo(Optional.ofNullable("dimension"))
                .setProperties(JsonHelper.convert(it, object : TypeReference<Map<String, Any>>() {}))
                .setType(getTrinoType(it.fieldType))
                .build()
        }

        private fun addColumnsForModel(columns: MutableList<ColumnMetadata>, dataset: Dataset, relation: Dataset.Relation?) {
            dataset.dimensions.forEach { dimension ->
                if (relation?.fields?.contains(dimension.name) != false) {
                    if (dimension.postOperations != null && !distinct) {
                        dimension.postOperations.forEach { timeframe ->
                            if (relation?.fields?.contains(dimension.name) != false) {
                                columns.add(toColumnMetadata(dimension, relation?.name, timeframe))
                            }
                        }
                    } else columns.add(toColumnMetadata(dimension, relation?.name))
                }
            }

            dataset.measures.filter { relation?.fields?.contains(it.name) != false }.mapNotNull { toColumnMetadata(it, relation?.name) }.forEach { columns.add(it) }
        }

        override fun getTableMetadata(): ConnectorTableMetadata {
            val columns = mutableListOf<ColumnMetadata>()

            addColumnsForModel(columns, dataset, null)

            dataset.relations?.forEach { relation ->
                datasets.find { it.name == relation.datasetName }?.let { model ->
                    addColumnsForModel(columns, model, relation)
                }
            }

            return ConnectorTableMetadata(
                SchemaTableName(dataset.category ?: "public", dataset.name),
                columns,
                mapOf("label" to dataset.label),
                Optional.ofNullable(dataset.description)
            )
        }
    }

    companion object {
        private val decimalType = DecimalType.createDecimalType()

        fun getTrinoType(type: FieldType?): Type {
            return when (type) {
                FieldType.INTEGER -> IntegerType.INTEGER
                FieldType.STRING -> VarcharType.createVarcharType(100)
                FieldType.DECIMAL -> decimalType
                FieldType.DOUBLE -> DoubleType.DOUBLE
                FieldType.LONG -> BigintType.BIGINT
                FieldType.BOOLEAN -> BooleanType.BOOLEAN
                FieldType.DATE -> DateType.DATE
                FieldType.TIME -> TimeType.TIME_MILLIS
                FieldType.TIMESTAMP -> TimestampType.TIMESTAMP_MILLIS
                FieldType.UNKNOWN, null -> UnknownType.UNKNOWN
                else -> {
                    if (type.isArray) {
                        ArrayType(getTrinoType(type.arrayElementType))
                    } else {
                        throw MetriqlException("$type type is not supported", HttpResponseStatus.BAD_REQUEST)
                    }
                }
            }
        }

        fun getMetriqlType(type: Type): FieldType {
            val name = type.baseName

            return when {
                IntegerType.INTEGER.baseName == name -> FieldType.INTEGER
                VarcharType.VARCHAR.baseName == name -> FieldType.STRING
                decimalType.baseName == name -> FieldType.DECIMAL
                DoubleType.DOUBLE.baseName == name -> FieldType.DOUBLE
                BigintType.BIGINT.baseName == name -> FieldType.LONG
                BooleanType.BOOLEAN.baseName == name -> FieldType.BOOLEAN
                DateType.DATE.baseName == name -> FieldType.DATE
                TimeType.TIME_MILLIS.baseName == name -> FieldType.TIME
                TimestampType.TIMESTAMP_MILLIS.baseName == name -> FieldType.TIMESTAMP
                TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS.baseName == name -> FieldType.TIMESTAMP
                type.baseName == "array" -> TODO()
                type.baseName == "map" -> TODO()
                type == UnknownType.UNKNOWN -> FieldType.UNKNOWN
                else -> throw UnsupportedOperationException("Unable to identify $type")
            }
        }
    }
}
