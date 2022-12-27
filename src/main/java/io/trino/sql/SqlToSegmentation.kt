package io.trino.sql

import com.google.common.base.Enums
import com.google.inject.Inject
import com.metriql.db.FieldType
import com.metriql.dbt.DbtJinjaRenderer
import com.metriql.report.data.FilterValue
import com.metriql.report.data.FilterValue.MetricFilter
import com.metriql.report.data.FilterValue.MetricType
import com.metriql.report.data.recipe.Recipe
import com.metriql.report.mql.MqlService
import com.metriql.report.segmentation.SegmentationQuery
import com.metriql.report.segmentation.SegmentationService
import com.metriql.service.dataset.Dataset
import com.metriql.service.dataset.Dataset.Measure.AggregationType.APPROXIMATE_UNIQUE
import com.metriql.service.dataset.Dataset.Measure.AggregationType.COUNT_UNIQUE
import com.metriql.service.dataset.Dataset.Measure.AggregationType.SUM
import com.metriql.service.dataset.DatasetName
import com.metriql.service.dataset.IDatasetService
import com.metriql.service.jdbc.StatementService.Companion.defaultParsingOptions
import com.metriql.util.JsonHelper
import com.metriql.util.MetriqlException
import com.metriql.util.ValidationUtil
import com.metriql.util.toSnakeCase
import com.metriql.warehouse.presto.PrestoMetriqlBridge.quoteIdentifier
import com.metriql.warehouse.presto.PrestoWarehouse
import com.metriql.warehouse.spi.bridge.WarehouseMetriqlBridge.AggregationContext.ADHOC
import com.metriql.warehouse.spi.filter.AnyOperatorType
import com.metriql.warehouse.spi.querycontext.IQueryGeneratorContext
import com.metriql.warehouse.spi.querycontext.TOTAL_ROWS_MEASURE
import io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST
import io.netty.handler.codec.http.HttpResponseStatus.NOT_IMPLEMENTED
import io.trino.MetriqlMetadata
import io.trino.sql.MetriqlExpressionFormatter.formatIdentifier
import io.trino.sql.tree.AllColumns
import io.trino.sql.tree.BetweenPredicate
import io.trino.sql.tree.BooleanLiteral
import io.trino.sql.tree.Cast
import io.trino.sql.tree.CharLiteral
import io.trino.sql.tree.ComparisonExpression
import io.trino.sql.tree.DereferenceExpression
import io.trino.sql.tree.DoubleLiteral
import io.trino.sql.tree.Expression
import io.trino.sql.tree.FunctionCall
import io.trino.sql.tree.GenericLiteral
import io.trino.sql.tree.Identifier
import io.trino.sql.tree.InListExpression
import io.trino.sql.tree.InPredicate
import io.trino.sql.tree.IsNotNullPredicate
import io.trino.sql.tree.IsNullPredicate
import io.trino.sql.tree.LikePredicate
import io.trino.sql.tree.Limit
import io.trino.sql.tree.Literal
import io.trino.sql.tree.LogicalBinaryExpression
import io.trino.sql.tree.LongLiteral
import io.trino.sql.tree.Node
import io.trino.sql.tree.NodeRef
import io.trino.sql.tree.NullLiteral
import io.trino.sql.tree.OrderBy
import io.trino.sql.tree.Parameter
import io.trino.sql.tree.Relation
import io.trino.sql.tree.Select
import io.trino.sql.tree.SelectItem
import io.trino.sql.tree.SingleColumn
import io.trino.sql.tree.SortItem
import io.trino.sql.tree.StringLiteral
import io.trino.sql.tree.Table
import io.trino.sql.tree.TimeLiteral
import io.trino.sql.tree.TimestampLiteral
import java.util.Optional

typealias Reference = Pair<MetricType, String>

data class ExpressionAliasProjection(val expression: Expression, val projection: String, val alias: String?, val identifier: Boolean)

class SqlToSegmentation @Inject constructor(val segmentationService: SegmentationService, val datasetService: IDatasetService) {
    private fun getProjectionOfColumn(
        rewriter: MetriqlSegmentationQueryRewriter,
        expression: Expression,
        modelName: String,
        alias: Optional<Identifier>
    ): ExpressionAliasProjection {
        val alias = alias.orElse(null)?.let { identifier -> identifier.value }
            ?: deferenceExpression(expression, modelName)?.let { exp -> getIdentifierValue(exp) }
        val projection = rewriter.process(expression, null)
        return ExpressionAliasProjection(expression, projection, alias, expression is Identifier)
    }

    fun convert(
        context: IQueryGeneratorContext,
        parameterMap: Map<NodeRef<Parameter>, Expression>,
        datasetAlias: Pair<Dataset, List<String>>,
        select: Select,
        where: Optional<Expression>,
        having: Optional<Expression>,
        limit: Optional<Node>,
        orderBy: Optional<OrderBy>
    ): String {
        val (model, alias) = datasetAlias

        val references = mutableMapOf<Node, Reference>()
        model.relations.forEach { relation ->
            val relationModel = context.getModel(relation.datasetName)
            buildReferences(references, relationModel, alias, relation = relation)
        }

        // override values if they already exist
        buildReferences(references, model, alias, relation = null)

        val measures = mutableListOf<String>()
        val dimensions = mutableListOf<String>()

        // TODO: see how we can use it
//        val groupings = query.groupBy.orElse(null)?.groupingElements?.flatMap {
//            if (it !is SimpleGroupBy) {
//                throw UnsupportedOperationException()
//            }
//            it.expressions.map { exp -> getReference(query.select.selectItems, exp) }
//        }?.toList() ?: listOf()

        val rewriter = MetriqlSegmentationQueryRewriter(context, model, references, dimensions, measures, parameterMap)

        val projectionColumns = select.selectItems.flatMap {
            when (it) {
                is AllColumns -> {
                    val metadata = MetriqlMetadata.ModelProxy(datasetService.list(context.auth), model, select.isDistinct).tableMetadata
                    metadata.columns.map { column ->
                        getProjectionOfColumn(rewriter, Identifier(column.name), model.name, Optional.empty())
                    }
                }
                is SingleColumn -> {
                    listOf(getProjectionOfColumn(rewriter, it.expression, model.name, it.alias))
                }
                else -> throw IllegalStateException()
            }
        }

        val whereFilters = where.orElse(null)?.let { processWhereExpression(context, rewriter, parameterMap, references, model, it) } ?: listOf()
        val havingFilters = having.orElse(null)?.let { processWhereExpression(context, rewriter, parameterMap, references, model, it) } ?: listOf()

        val (havingFilterProjections, havingFiltersPushdown) = havingFilters.groupBy { it is FilterValue.SqlFilter }?.let { Pair(it[true] ?: listOf(), it[false] ?: listOf()) }
        val (whereFilterProjections, whereFiltersPushdown) = whereFilters.groupBy { it is FilterValue.SqlFilter }?.let { Pair(it[true] ?: listOf(), it[false] ?: listOf()) }

        val (projectionOrders, orders) = parseOrders(rewriter, references, select.selectItems, projectionColumns, orderBy)
        val query = SegmentationQuery(
            model.name,
            dimensions.toSet().map { Recipe.FieldReference.fromName(it) },
            measures.toSet().map { Recipe.FieldReference.fromName(it) },
            FilterValue.NestedFilter(FilterValue.NestedFilter.Connector.AND, whereFiltersPushdown + havingFiltersPushdown),
            limit = parseLimit(limit.orElse(null)),
            orders = orders
        )

        val (renderedQuery, _, _) = segmentationService.renderQuery(context.auth, context, query)

        val projectionNeeded = projectionColumns.any { (it.projection != it.alias && it.alias != null) || !it.identifier }
        val filterNeeded = havingFilterProjections.isNotEmpty() || whereFilterProjections.isNotEmpty()

        return if (projectionNeeded || select.isDistinct || filterNeeded) {
            val quotedAlias = context.warehouseBridge.quoteIdentifier(alias[alias.size - 1])
            val projections = if (projectionNeeded) projectionColumns.joinToString(", ") { col ->
                val alias = col.alias?.let { context.warehouseBridge.quoteIdentifier(it) }
                if (col.projection != col.alias) "${col.projection}${alias?.let { " AS $it" } ?: ""}" else col.projection
            } else "*"

            val orderBy = if (projectionOrders.any { it != null }) "\nORDER BY ${projectionOrders.joinToString(" ")}" else ""

            // We don't need to use HAVING as we have sub-query
            val whereFilters = (whereFilterProjections + havingFilterProjections).map { it as FilterValue.SqlFilter }.joinToString(" AND ") { it.sql }

            """SELECT ${if (select.isDistinct) "DISTINCT " else ""}$projections FROM (
                |$renderedQuery
                |) AS $quotedAlias 
                |${if (whereFilters.isNotEmpty()) "\nWHERE $whereFilters" else ""}
                |$orderBy""".trimMargin()
        } else {
            renderedQuery
        }
    }

    private fun deferenceExpression(expression: Expression, datasetName: DatasetName?): Node? {
        return when (expression) {
            is DereferenceExpression -> {
                val source = getIdentifierValue(expression.base)
                if (source != datasetName) {
                    throw IllegalArgumentException("Invalid reference $source")
                }
                expression.field
            }
            is Identifier -> expression
            is FunctionCall -> Identifier(expression.name.suffix)
            else -> null
        }
    }

    private fun getIdentifierValue(expression: Node): String {
        return when (expression) {
            is Identifier -> {
                expression.value
            }
            else -> TODO()
        }
    }

    inner class MetriqlSegmentationQueryRewriter(
        context: IQueryGeneratorContext,
        private val dataset: Dataset,
        private val references: Map<Node, Reference>,
        val dimensions: MutableList<String>,
        val measures: MutableList<String>,
        parameterMap: Map<NodeRef<Parameter>, Expression>,
    ) : MetriqlExpressionFormatter.Formatter(this, context, parameterMap) {

        override fun visitFunctionCall(node: FunctionCall, context: Void?): String? {
            if (node.name.prefix.isPresent) {
                throw UnsupportedOperationException("schema functions are not supported")
            }

            var directReference = references[node]
            val reference = if (directReference != null) {
                measures.add(directReference.second)
                directReference
            } else if (isPlain(node)) {
                // workaround for basic count(*) as we have a system measure for it
                val isTotalRows = when (node.name.suffix) {
                    "count" -> {
                        when {
                            node.arguments.isEmpty() -> true
                            node.arguments[0] is Literal -> true
                            else -> false
                        }
                    }
                    "sum" -> {
                        when (val arg = node.arguments[0]) {
                            is DoubleLiteral -> arg.value == 1.0
                            is LongLiteral -> arg.value == 1L
                            else -> false
                        }
                    }
                    else -> false
                }

                if (isTotalRows) {
                    measures.add(TOTAL_ROWS_MEASURE.name)
                    MetricType.MEASURE to TOTAL_ROWS_MEASURE.name
                } else null
            } else null

            return if (reference != null) {
                val ref = Recipe.FieldReference.fromName(reference.second)

                val alias = when (reference.first) {
                    MetricType.MEASURE -> queryContext.getMeasureAlias(ref.name, ref.relation)
                    MetricType.DIMENSION -> queryContext.getDimensionAlias(ref.name, ref.relation, null)
                    else -> throw IllegalStateException()
                }

                return formatIdentifier(alias, queryContext)
            } else {
                super.visitFunctionCall(node, context)
            }
        }

        private fun isPlain(node: FunctionCall): Boolean {
            return !node.isDistinct && node.filter.isEmpty &&
                node.nullTreatment.isEmpty && node.window.isEmpty && node.processingMode.isEmpty
        }

        override fun visitDereferenceExpression(node: DereferenceExpression, context: Void?): String? {
            val reference = references[node]
            return if (reference != null) {
                rewriteValueForReference(reference, node.field.value)
            } else {
                super.visitDereferenceExpression(node, context)
            }
        }

        override fun visitIdentifier(node: Identifier, context: Void?): String {
            val reference = references[node]
            return if (reference != null) {
                rewriteValueForReference(reference, node.value)
            } else {
                throw MetriqlException("Not found $node", BAD_REQUEST)
            }
        }

        private fun rewriteValueForReference(reference: Reference, value: String): String {
            return when (reference.first) {
                MetricType.DIMENSION -> {
                    dimensions.add(reference.second)
                    val ref = Recipe.FieldReference.fromName(value)
                    val dimension = ref.toDimension(dataset.name, ref.getType(queryContext, dataset.name).second)
                    queryContext.getDimensionAlias(dimension.name, dimension.relation, dimension.timeframe)
                }
                MetricType.MEASURE -> {
                    measures.add(reference.second)
                    val ref = Recipe.FieldReference.fromName(value)
                    queryContext.getMeasureAlias(ref.name, ref.relation)
                }
                else -> TODO()
            }
        }
    }

    private fun getReference(selectItems: List<SelectItem>, resolvedSelectItems: List<ExpressionAliasProjection>, exp: Expression): Expression {
        return when (exp) {
            is LongLiteral -> {
                val index = exp.value.toInt() - 1
                if (resolvedSelectItems.size <= index) {
                    throw MetriqlException("Unable to parse long literal ${exp.value}", BAD_REQUEST)
                }
                resolvedSelectItems[index].expression
            }
            is Identifier -> {
                val singleSelectItem = selectItems.find {
                    when (it) {
                        is SingleColumn -> it.alias.orElse(null) == exp
                        else -> false
                    }
                }

                (singleSelectItem as? SingleColumn)?.expression ?: exp
            }
            else -> exp
        }
    }

    private fun parseOrders(
        rewriter: MetriqlSegmentationQueryRewriter,
        references: Map<Node, Reference>,
        selectItems: MutableList<SelectItem>,
        projectionColumns: List<ExpressionAliasProjection>,
        orderBy: Optional<OrderBy>
    ): Pair<List<String?>, Map<Recipe.FieldReference, Recipe.OrderType>?> {
        if (!orderBy.isPresent) {
            return listOf<String>() to null
        }

        val map = orderBy.get().sortItems.map {
            val orderType = when (it.ordering) {
                SortItem.Ordering.DESCENDING -> Recipe.OrderType.DESC
                SortItem.Ordering.ASCENDING -> Recipe.OrderType.ASC
            }
            if (it.nullOrdering != SortItem.NullOrdering.UNDEFINED) {
                throw MetriqlException("NULL ORDERING is not supported yet", BAD_REQUEST)
            }
            val reference = getReference(selectItems, projectionColumns, it.sortKey)
            reference to orderType
        }

        val queryOrders = map.mapNotNull {
            val metric = references[it.first]?.second?.let { ref -> Recipe.FieldReference.fromName(ref) }
            if (metric == null) {
                null
            } else {
                metric to it.second
            }
        }

        val projectionOrders = map.map {
            val metric = references[it.first]?.second?.let { ref -> Recipe.FieldReference.fromName(ref) }
            if (metric == null) {
                rewriter.process(it.first)
            } else null
        }

        return projectionOrders to queryOrders.toMap()
    }

    private fun getReportFilter(
        context: IQueryGeneratorContext,
        dataset: Dataset,
        metricReference: Reference,
        operatorFunction: (FieldType) -> Enum<*>,
        value: Any?
    ): List<FilterValue> {
        val (type, metricValue) = when (metricReference.first) {
            MetricType.DIMENSION -> {
                val fromName = Recipe.FieldReference.fromName(metricReference.second)
                val (_, type) = fromName.getType(context, dataset.name)
                type to fromName
            }
            MetricType.MEASURE -> FieldType.DOUBLE to Recipe.FieldReference.fromName(metricReference.second)
            else -> throw IllegalStateException()
        }

        return listOf(
            MetricFilter(metricValue, operatorFunction.invoke(type).name, value)
        )
    }

    private fun processWhereExpression(
        context: IQueryGeneratorContext,
        rewriter: MetriqlSegmentationQueryRewriter,
        parameterMap: Map<NodeRef<Parameter>, Expression>,
        references: Map<Node, Reference>,
        dataset: Dataset,
        exp: Expression
    ): List<FilterValue>? {

        return when (exp) {
            is IsNullPredicate -> {
                val metricReference = references[exp.value] ?: throw MetriqlException("Unable to resolve ${exp.value}", BAD_REQUEST)
                getReportFilter(context, dataset, metricReference, { AnyOperatorType.IS_NOT_SET }, null)
            }
            is IsNotNullPredicate -> {
                val metricReference = references[exp.value] ?: throw MetriqlException("Unable to resolve ${exp.value}", BAD_REQUEST)
                getReportFilter(context, dataset, metricReference, { AnyOperatorType.IS_SET }, null)
            }
            is InPredicate -> {
                val metricReference = references[exp.value] ?: throw MetriqlException("Unable to resolve ${exp.value}", BAD_REQUEST)
                val value = exp.valueList as? InListExpression ?: throw MetriqlException("Unable to resolve $exp, value must be a list", BAD_REQUEST)
                getReportFilter(
                    context, dataset, metricReference,
                    {
                        Enums.getIfPresent(it.operatorClass.java, "IN").orNull() ?: throw MetriqlException("IN operator is not available for $it type", BAD_REQUEST)
                    },
                    value.values.map { ValueExtractorVisitor().process(it) }.toList()
                )
            }
            is BetweenPredicate -> {
                val metricReference = references[exp.value] ?: TODO()
                getReportFilter(
                    context, dataset, metricReference,
                    {
                        exp.min
                        TODO()
                    },
                    null
                )
            }
            is LikePredicate -> {
                val metricReference = references[exp.value] ?: TODO()
                getReportFilter(
                    context, dataset, metricReference,
                    {
                        exp.value
                        TODO()
                    },
                    null
                )
            }
            is LogicalBinaryExpression -> {
                val left = processWhereExpression(context, rewriter, parameterMap, references, dataset, exp.left)
                val right = processWhereExpression(context, rewriter, parameterMap, references, dataset, exp.right)
                val allFilters = (left ?: listOf()) + (right ?: listOf())

                when (exp.operator) {
                    LogicalBinaryExpression.Operator.AND -> allFilters
                    LogicalBinaryExpression.Operator.OR -> {
                        val filters = allFilters.flatMap { filter ->
                            when (filter) {
                                is FilterValue.SqlFilter -> throw UnsupportedOperationException()
                                is MetricFilter -> listOf(filter)
                                is FilterValue.NestedFilter -> TODO()
                            }
                        }
                        listOf(FilterValue.NestedFilter(FilterValue.NestedFilter.Connector.OR, filters = filters))
                    }
                }
            }
            is ComparisonExpression -> {
                val isRedundant = if (exp.left is Literal && exp.right is Literal) {
                    when (exp.operator) {
                        ComparisonExpression.Operator.EQUAL -> {
                            exp.left == exp.right
                        }
                        ComparisonExpression.Operator.NOT_EQUAL -> {
                            exp.left != exp.right
                        }
                        else -> throw MetriqlException("${exp.operator} is not supported yet", NOT_IMPLEMENTED)
                    }
                } else null

                when (isRedundant) {
                    true -> listOf()
                    false -> null
                    else -> {
                        val metricReference =
                            references[exp.left] ?: references[exp.right] ?: return listOf(FilterValue.SqlFilter(rewriter.process(exp)))
                        val value = getFilterValue(parameterMap, if (references.containsKey(exp.left)) exp.right else exp.left)
                        getReportFilter(context, dataset, metricReference, { convertMetriqlOperator(exp.operator, it.operatorClass.java) }, value)
                    }
                }
            }
            is BooleanLiteral -> {
                if (exp.value) {
                    // redundant filter
                    null
                } else {
                    throw UnsupportedOperationException("`false` value is not supported in WHERE condition")
                }
            }
            else -> throw UnsupportedOperationException("${exp.javaClass.name} statement is not supported in WHERE condition")
        }
    }

    private fun convertMetriqlOperator(operator: ComparisonExpression.Operator, clazz: Class<out Enum<*>>): Enum<*> {
        return when (operator) {
            ComparisonExpression.Operator.EQUAL -> JsonHelper.convert("equals", clazz)
            ComparisonExpression.Operator.NOT_EQUAL -> JsonHelper.convert("notEquals", clazz)
            ComparisonExpression.Operator.LESS_THAN -> JsonHelper.convert("lessThan", clazz)
            ComparisonExpression.Operator.GREATER_THAN -> JsonHelper.convert("greaterThan", clazz)
            ComparisonExpression.Operator.LESS_THAN_OR_EQUAL -> JsonHelper.convert("lessThanOrEqual", clazz)
            ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL -> JsonHelper.convert("greaterThanOrEqual", clazz)
            ComparisonExpression.Operator.IS_DISTINCT_FROM -> TODO()
        }
    }

    private fun parseLimit(limit: Node?): Int? {
        return when (limit) {
            null -> null
            is Limit -> {
                when (val count = limit.rowCount) {
                    is LongLiteral -> count.value.toInt()
                    else -> TODO()
                }
            }
            else -> TODO()
        }
    }

    private fun getFilterValue(parameterMap: Map<NodeRef<Parameter>, Expression>, exp: Expression): Any? {
        return when (exp) {
            is LongLiteral -> exp.value
            is DoubleLiteral -> exp.value
            is StringLiteral -> exp.value
            is TimestampLiteral -> exp.value
            is TimeLiteral -> exp.value
            is CharLiteral -> exp.value
            is GenericLiteral -> exp.value
            is NullLiteral -> null
            is BooleanLiteral -> exp.value
            is Parameter -> getFilterValue(parameterMap, parameterMap[NodeRef.of(exp)]!!)
            is Cast -> {
                getFilterValue(parameterMap, exp.expression)
            }
            else -> {
                throw MetriqlException("Only scalar values are supported in WHERE. Expression is not supported: $exp ", BAD_REQUEST)
            }
        }
    }

    private fun getMeasureStr(measure: Dataset.Measure, tableAlias: List<String>, prefix: String?): List<String> {
        val aggregations = when {
            measure.value.agg == APPROXIMATE_UNIQUE -> {
                listOf(APPROXIMATE_UNIQUE, COUNT_UNIQUE, SUM, null)
            }
            measure.value.agg != null -> listOf(measure.value.agg, SUM, null)
            else -> listOf(SUM, null)
        }

        val suffix = ValidationUtil.quoteIdentifier("${prefix?.let { "$it." } ?: ""}${measure.name}")
        return tableAlias.flatMapIndexed { index, _ ->
            val al = tableAlias.subList(index, tableAlias.size).joinToString(".") { ValidationUtil.quoteIdentifier(it) }
            val columnValues = listOf(suffix, "$al.$suffix")

            columnValues.flatMap {
                aggregations.map { aggregation ->
                    if (aggregation != null) {
                        PrestoWarehouse.bridge.performAggregation(it, aggregation, ADHOC)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun getDimensionStr(dimension: Dataset.Dimension, tableAlias: List<String>, prefix: String?): List<Pair<String, Reference>> {

        // the mapping is based on presto dialect
        val identifier = "${prefix?.let { "$it." } ?: ""}${dimension.name}"
        val baseReference = Pair(MetricType.DIMENSION, identifier)

        var quotedIdentifier = quoteIdentifier(identifier)
        val rawDimension = tableAlias.flatMapIndexed { index, _ ->
            listOf(
                quotedIdentifier to baseReference,
                tableAlias.subList(index, tableAlias.size).joinToString(".") { quoteIdentifier(it) } + "." + quotedIdentifier to baseReference
            )
        }
        return if (dimension.postOperations == null) {
            rawDimension
        } else {
            rawDimension + dimension.postOperations.flatMap {
                val identifier = "$identifier::${toSnakeCase(it)}"
                val quotedIdentifier = quoteIdentifier(identifier)
                val reference = Pair(MetricType.DIMENSION, identifier)

                tableAlias.flatMapIndexed { index, _ ->
                    listOf(
                        quotedIdentifier to reference,
                        tableAlias.subList(index, tableAlias.size).joinToString(".") { ref -> quoteIdentifier(ref) } + "." + quotedIdentifier to reference
                    )
                }
            }
        }
    }

    private fun buildReferences(
        references: MutableMap<Node, Reference>,
        sourceDataset: Dataset,
        tableAlias: List<String>,
        relation: Dataset.Relation? = null
    ) {

        sourceDataset.dimensions.forEach { dimension ->
            getDimensionStr(dimension, tableAlias, relation?.name).forEach { references[parseExpression(it.first)] = it.second }
        }

        sourceDataset.measures.forEach { measure ->
            val prefix = relation?.name?.let { "$it." } ?: ""
            val reference = Pair(MetricType.MEASURE, prefix + measure.name)
            getMeasureStr(measure, tableAlias, relation?.name).forEach { references[parseExpression(it)] = reference }
        }
    }

    private fun parseExpression(expression: String): Expression {
        return MqlService.parser.createExpression(expression, defaultParsingOptions)
    }

    companion object {
        fun getModel(context: IQueryGeneratorContext, from: Relation): Pair<Dataset, List<String>> {
            return when (from) {
                is Table -> {
                    val table = DbtJinjaRenderer.renderer.renderModelNameRegex(from.name.suffix)
                    val model = context.getModel(table)
                    Pair(model, listOfNotNull(from.name.prefix.orElse(null)?.suffix, model.name))
                }
                else -> {
                    throw UnsupportedOperationException(String.format("Unsupported operation %s", from.javaClass.name))
                }
            }
        }
    }
}
