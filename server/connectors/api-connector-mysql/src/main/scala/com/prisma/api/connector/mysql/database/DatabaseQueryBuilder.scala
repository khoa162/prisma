package com.prisma.api.connector.mysql.database

import com.prisma.api.connector.Types.DataItemFilterCollection
import com.prisma.api.connector._
import com.prisma.api.connector.mysql.database.DatabaseMutationBuilder.idFromWhereEquals
import com.prisma.api.connector.mysql.database.HelperTypes.ScalarListElement
import com.prisma.gc_values._
import com.prisma.shared.models.{Function => _, _}
import org.joda.time.DateTime
import play.api.libs.json.Json
import slick.dbio.DBIOAction
import slick.dbio.Effect.Read
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.meta.{DatabaseMeta, MTable}
import slick.jdbc.{SQLActionBuilder, _}
import slick.sql.SqlStreamingAction

import scala.concurrent.ExecutionContext.Implicits.global

object DatabaseQueryBuilder {

  import JdbcExtensions._
  import QueryArgumentsExtensions._
  import SlickExtensions._

  implicit object GetDataItem extends GetResult[DataItem] {
    def apply(ps: PositionedResult): DataItem = {
      val rs = ps.rs
      val md = rs.getMetaData
      val colNames = for (i <- 1 to md.getColumnCount)
        yield md.getColumnName(i)

      val userData = (for (n <- colNames.filter(_ != "id"))
        // note: getObject(string) is case insensitive, so we get the index in scala land instead
        yield n -> Option(rs.getObject(colNames.indexOf(n) + 1))).toMap

      DataItem(id = rs.getString("id").trim, userData = userData)
    }
  }

  def getResultForModel(model: Model): GetResult[PrismaNode] = GetResult { ps: PositionedResult =>
    val resultSet = ps.rs
    val id        = resultSet.getString("id")
    val data = model.scalarNonListFields.map { field =>
      val gcValue: GCValue = field.typeIdentifier match {
        case TypeIdentifier.String    => StringGCValue(resultSet.getString(field.name))
        case TypeIdentifier.GraphQLID => GraphQLIdGCValue(resultSet.getString(field.name))
        case TypeIdentifier.Enum      => EnumGCValue(resultSet.getString(field.name))
        case TypeIdentifier.Int       => IntGCValue(resultSet.getInt(field.name))
        case TypeIdentifier.Float     => FloatGCValue(resultSet.getDouble(field.name))
        case TypeIdentifier.Boolean   => BooleanGCValue(resultSet.getBoolean(field.name))
        case TypeIdentifier.DateTime =>
          val sqlType = resultSet.getTimestamp(field.name)
          if (sqlType != null) {
            DateTimeGCValue(new DateTime(sqlType.getTime))
          } else {
            NullGCValue
          }
        case TypeIdentifier.Json =>
          val sqlType = resultSet.getString(field.name)
          if (sqlType != null) {
            JsonGCValue(Json.parse(sqlType))
          } else {
            NullGCValue
          }
        case TypeIdentifier.Relation => sys.error("TypeIdentifier.Relation is not supported here")
      }
      if (resultSet.wasNull) { // todo: should we throw here if the field is required but it is null?
        field.name -> NullGCValue
      } else {
        field.name -> gcValue
      }
    }

    PrismaNode(id = id, data = RootGCValue(data: _*))
  }

  implicit object GetRelationNode extends GetResult[RelationNode] {
    override def apply(ps: PositionedResult): RelationNode = {
      val resultSet = ps.rs
      val id        = resultSet.getString("id")
      val a         = resultSet.getString("A")
      val b         = resultSet.getString("B")
      RelationNode(id, a, b)
    }
  }

  def getResultForScalarListField(field: Field): GetResult[ScalarListElement] = GetResult { ps: PositionedResult =>
    val resultSet = ps.rs
    val nodeId    = resultSet.getString("nodeId")
    val position  = resultSet.getInt("position")
    val value     = resultSet.getGcValue("value", field.typeIdentifier)
    ScalarListElement(nodeId, position, value)
  }

  implicit object GetScalarListValue extends GetResult[ScalarListValue] {
    def apply(ps: PositionedResult): ScalarListValue = {
      val rs = ps.rs

      ScalarListValue(nodeId = rs.getString("nodeId").trim, position = rs.getInt("position"), value = rs.getObject("value"))
    }
  }

  def selectAllFromTable(
      projectId: String,
      tableName: String,
      args: Option[QueryArguments]
  ): (SQLActionBuilder, ResultTransform) = {

    val (conditionCommand, orderByCommand, limitCommand, resultTransform) = extractQueryArgs(projectId, tableName, args, overrideMaxNodeCount = None)

    val query =
      sql"select * from `#$projectId`.`#$tableName`" concat
        prefixIfNotNone("where", conditionCommand) concat
        prefixIfNotNone("order by", orderByCommand) concat
        prefixIfNotNone("limit", limitCommand)

    (query, resultTransform)
  }

  def selectAllFromTableNew(
      projectId: String,
      model: Model,
      args: Option[QueryArguments],
      overrideMaxNodeCount: Option[Int] = None
  ): DBIOAction[ResolverResultNew[PrismaNode], NoStream, Effect] = {

    val tableName                                           = model.name
    val (conditionCommand, orderByCommand, limitCommand, _) = extractQueryArgs(projectId, tableName, args, overrideMaxNodeCount = overrideMaxNodeCount)

    val query = sql"select * from `#$projectId`.`#$tableName`" concat
      prefixIfNotNone("where", conditionCommand) concat
      prefixIfNotNone("order by", orderByCommand) concat
      prefixIfNotNone("limit", limitCommand)

    query.as[PrismaNode](getResultForModel(model)).map { nodes =>
      ResolverResultNew(
        nodes = args.get.dropExtraLimitItem(nodes),
        hasNextPage = args.get.hasNext(nodes.size),
        hasPreviousPage = args.get.hasPrevious(nodes.size)
      )
    }
  }

  def selectAllFromRelationTable(
      projectId: String,
      relationId: String,
      args: Option[QueryArguments],
      overrideMaxNodeCount: Option[Int] = None
  ): DBIOAction[ResolverResultNew[RelationNode], NoStream, Effect] = {

    val tableName                                           = relationId
    val (conditionCommand, orderByCommand, limitCommand, _) = extractQueryArgs(projectId, tableName, args, overrideMaxNodeCount = overrideMaxNodeCount)

    val query = sql"select * from `#$projectId`.`#$tableName`" concat
      prefixIfNotNone("where", conditionCommand) concat
      prefixIfNotNone("order by", orderByCommand) concat
      prefixIfNotNone("limit", limitCommand)

    query.as[RelationNode].map { nodes =>
      ResolverResultNew(
        nodes = args.get.dropExtraLimitItem(nodes),
        hasNextPage = args.get.hasNext(nodes.size),
        hasPreviousPage = args.get.hasPrevious(nodes.size)
      )
    }
  }

  def selectAllFromListTable(projectId: String,
                             model: Model,
                             field: Field,
                             args: Option[QueryArguments],
                             overrideMaxNodeCount: Option[Int] = None): SqlStreamingAction[Vector[ScalarListElement], ScalarListElement, Read] = {

    val tableName                                           = s"${model.name}_${field.name}"
    val (conditionCommand, orderByCommand, limitCommand, _) = extractListQueryArgs(projectId, tableName, args, overrideMaxNodeCount = overrideMaxNodeCount)

    val query =
      sql"select * from `#$projectId`.`#$tableName`" concat
        prefixIfNotNone("where", conditionCommand) concat
        prefixIfNotNone("order by", orderByCommand) concat
        prefixIfNotNone("limit", limitCommand)

    query.as[ScalarListElement](getResultForScalarListField(field))
  }

  def countAllFromModel(project: Project, model: Model, where: Option[DataItemFilterCollection]): SQLActionBuilder = {
    val whereSql = where.flatMap { where =>
      QueryArgumentsHelpers.generateFilterConditions(project.id, model.name, where)
    }
    sql"select count(*) from `#${project.id}`.`#${model.name}`" ++ prefixIfNotNone("where", whereSql)
  }

  def extractQueryArgs(
      projectId: String,
      modelName: String,
      args: Option[QueryArguments],
      defaultOrderShortcut: Option[String] = None,
      overrideMaxNodeCount: Option[Int] = None): (Option[SQLActionBuilder], Option[SQLActionBuilder], Option[SQLActionBuilder], ResultTransform) = {
    args match {
      case None => (None, None, None, x => ResolverResult(x))
      case Some(givenArgs: QueryArguments) =>
        (
          givenArgs.extractWhereConditionCommand(projectId, modelName),
          givenArgs.extractOrderByCommand(projectId, modelName, defaultOrderShortcut),
          overrideMaxNodeCount match {
            case None                => givenArgs.extractLimitCommand(projectId, modelName)
            case Some(maxCount: Int) => givenArgs.extractLimitCommand(projectId, modelName, maxCount)
          },
          givenArgs.extractResultTransform(projectId, modelName)
        )
    }
  }

  def extractListQueryArgs(
      projectId: String,
      modelName: String,
      args: Option[QueryArguments],
      defaultOrderShortcut: Option[String] = None,
      overrideMaxNodeCount: Option[Int] = None): (Option[SQLActionBuilder], Option[SQLActionBuilder], Option[SQLActionBuilder], ResultListTransform) = {
    args match {
      case None =>
        (None,
         None,
         None,
         x =>
           ResolverResult(x.map { listValue =>
             DataItem(id = listValue.nodeId, userData = Map("value" -> Some(listValue.value)))
           }))
      case Some(givenArgs: QueryArguments) =>
        (
          givenArgs.extractWhereConditionCommand(projectId, modelName),
          givenArgs.extractOrderByCommandForLists(projectId, modelName, defaultOrderShortcut),
          overrideMaxNodeCount match {
            case None                => givenArgs.extractLimitCommand(projectId, modelName)
            case Some(maxCount: Int) => givenArgs.extractLimitCommand(projectId, modelName, maxCount)
          },
          givenArgs.extractListResultTransform(projectId, modelName)
        )
    }
  }

  def itemCountForTable(projectId: String, modelName: String) = {
    sql"SELECT COUNT(*) AS Count FROM `#$projectId`.`#$modelName`"
  }

  def existsNullByModelAndScalarField(projectId: String, modelName: String, fieldName: String) = {
    sql"""SELECT EXISTS(Select `id` FROM `#$projectId`.`#$modelName`
          WHERE `#$projectId`.`#$modelName`.#$fieldName IS NULL)"""
  }

  def existsNullByModelAndRelationField(projectId: String, modelName: String, field: Field) = {
    val relationId   = field.relation.get.id
    val relationSide = field.relationSide.get.toString
    sql"""select EXISTS (
            select `id`from `#$projectId`.`#$modelName`
            where `id` Not IN
            (Select `#$projectId`.`#$relationId`.#$relationSide from `#$projectId`.`#$relationId`)
          )"""
  }

  def existsNodeIsInRelationshipWith(project: Project, path: Path) = {
    def nodeSelector(last: Edge) = last match {
      case edge: NodeEdge => sql" `id`" ++ idFromWhereEquals(project.id, edge.childWhere) ++ sql" AND "
      case _: ModelEdge   => sql""
    }

    sql"""select EXISTS (
            select `id`from `#${project.id}`.`#${path.lastModel.name}`
            where""" ++ nodeSelector(path.lastEdge_!) ++ sql""" `id` IN""" ++ DatabaseMutationBuilder.pathQueryThatUsesWholePath(project.id, path) ++ sql")"
  }

  def existsByModelAndId(projectId: String, modelName: String, id: String) = {
    sql"select exists (select `id` from `#$projectId`.`#$modelName` where `id` = '#$id')"
  }

  def existsByModel(projectId: String, modelName: String): SQLActionBuilder = {
    sql"select exists (select `id` from `#$projectId`.`#$modelName`)"
  }

  def batchSelectFromModelByUnique(projectId: String, modelName: String, key: String, values: List[Any]): SQLActionBuilder = {
    sql"select * from `#$projectId`.`#$modelName` where `#$key` in (" concat combineByComma(values.map(escapeUnsafeParam)) concat sql")"
  }

  def selectFromModelsByUniques(project: Project, model: Model, predicates: Vector[NodeSelector]) = {
    sql"select * from `#${project.id}`.`#${model.name}`" ++ whereClauseByCombiningPredicatesByOr(predicates)
  }

  def existsByWhere(projectId: String, where: NodeSelector) = {
    sql"select exists (select `id` from `#$projectId`.`#${where.model.name}` where  #${where.field.name} = ${where.fieldValue})"
  }

  def existsByPath(projectId: String, path: Path) = {
    if (path.edges.isEmpty && path.root.isId) {
      sql"select exists (SELECT * FROM `#$projectId`.`#${path.root.model.name}` WHERE `id` = ${path.root.fieldValue})"
    } else {
      sql"select exists" ++ DatabaseMutationBuilder.pathQueryForLastChild(projectId, path)
    }
  }

  def selectFromScalarList(projectId: String, modelName: String, fieldName: String, nodeIds: Vector[String]): SQLActionBuilder = {
    sql"select nodeId, position, value from `#$projectId`.`#${modelName}_#$fieldName` where nodeId in (" concat combineByComma(nodeIds.map(escapeUnsafeParam)) concat sql")"
  }

  def whereClauseByCombiningPredicatesByOr(predicates: Vector[NodeSelector]) = {
    if (predicates.isEmpty) {
      sql""
    } else {
      val firstPredicate = predicates.head
      predicates.tail.foldLeft(sql"where #${firstPredicate.field.name} = ${firstPredicate.fieldValue}") { (sqlActionBuilder, predicate) =>
        sqlActionBuilder ++ sql" OR #${predicate.field.name} = ${predicate.fieldValue}"
      }
    }
  }

  def batchSelectAllFromRelatedModel(project: Project,
                                     relationField: Field,
                                     parentNodeIds: List[String],
                                     args: Option[QueryArguments]): (SQLActionBuilder, ResultTransform) = {

    val fieldTable        = relationField.relatedModel(project.schema).get.name
    val unsafeRelationId  = relationField.relation.get.id
    val modelRelationSide = relationField.relationSide.get.toString
    val fieldRelationSide = relationField.oppositeRelationSide.get.toString

    val (conditionCommand, orderByCommand, limitCommand, resultTransform) =
      extractQueryArgs(project.id, fieldTable, args, defaultOrderShortcut = Some(s"""`${project.id}`.`$unsafeRelationId`.$fieldRelationSide"""))

    def createQuery(id: String, modelRelationSide: String, fieldRelationSide: String) = {
      sql"""(select * from `#${project.id}`.`#$fieldTable`
           inner join `#${project.id}`.`#$unsafeRelationId`
           on `#${project.id}`.`#$fieldTable`.id = `#${project.id}`.`#$unsafeRelationId`.#$fieldRelationSide
           where `#${project.id}`.`#$unsafeRelationId`.#$modelRelationSide = '#$id' """ concat
        prefixIfNotNone("and", conditionCommand) concat
        prefixIfNotNone("order by", orderByCommand) concat
        prefixIfNotNone("limit", limitCommand) concat sql")"
    }

    def unionIfNotFirst(index: Int): SQLActionBuilder =
      if (index == 0) {
        sql""
      } else {
        sql"union all "
      }

    // see https://github.com/graphcool/internal-docs/blob/master/relations.md#findings
    val resolveFromBothSidesAndMerge = relationField.relation.get
      .isSameFieldSameModelRelation(project.schema) && !relationField.isList

    val query = resolveFromBothSidesAndMerge match {
      case false =>
        parentNodeIds.distinct.view.zipWithIndex.foldLeft(sql"")((a, b) =>
          a concat unionIfNotFirst(b._2) concat createQuery(b._1, modelRelationSide, fieldRelationSide))

      case true =>
        parentNodeIds.distinct.view.zipWithIndex.foldLeft(sql"")(
          (a, b) =>
            a concat unionIfNotFirst(b._2) concat createQuery(b._1, modelRelationSide, fieldRelationSide) concat sql"union all " concat createQuery(
              b._1,
              fieldRelationSide,
              modelRelationSide))
    }

    (query, resultTransform)
  }

  def countAllFromRelatedModels(project: Project,
                                relationField: Field,
                                parentNodeIds: List[String],
                                args: Option[QueryArguments]): (SQLActionBuilder, ResultTransform) = {

    val fieldTable        = relationField.relatedModel(project.schema).get.name
    val unsafeRelationId  = relationField.relation.get.id
    val modelRelationSide = relationField.relationSide.get.toString
    val fieldRelationSide = relationField.oppositeRelationSide.get.toString

    val (conditionCommand, orderByCommand, limitCommand, resultTransform) =
      extractQueryArgs(project.id, fieldTable, args, defaultOrderShortcut = Some(s"""`${project.id}`.`$unsafeRelationId`.$fieldRelationSide"""))

    def createQuery(id: String) = {
      sql"""(select '#$id', count(*) from `#${project.id}`.`#$fieldTable`
           inner join `#${project.id}`.`#$unsafeRelationId`
           on `#${project.id}`.`#$fieldTable`.id = `#${project.id}`.`#$unsafeRelationId`.#$fieldRelationSide
           where `#${project.id}`.`#$unsafeRelationId`.#$modelRelationSide = '#$id' """ concat
        prefixIfNotNone("and", conditionCommand) concat
        prefixIfNotNone("order by", orderByCommand) concat
        prefixIfNotNone("limit", limitCommand) concat sql")"
    }

    def unionIfNotFirst(index: Int): SQLActionBuilder =
      if (index == 0) {
        sql""
      } else {
        sql"union all "
      }

    val query =
      parentNodeIds.distinct.view.zipWithIndex.foldLeft(sql"")((a, b) => a concat unionIfNotFirst(b._2) concat createQuery(b._1))

    (query, resultTransform)
  }

  case class ColumnDescription(name: String, isNullable: Boolean, typeName: String, size: Option[Int])
  case class IndexDescription(name: Option[String], nonUnique: Boolean, column: Option[String])
  case class ForeignKeyDescription(name: Option[String], column: String, foreignTable: String, foreignColumn: String)
  case class TableInfo(columns: List[ColumnDescription], indexes: List[IndexDescription], foreignKeys: List[ForeignKeyDescription])

  def getTables(projectId: String): DBIOAction[Vector[String], NoStream, Read] = {
    for {
      metaTables <- MTable.getTables(cat = Some(projectId), schemaPattern = None, namePattern = None, types = None)
    } yield metaTables.map(table => table.name.name)
  }

  def getSchemas: DBIOAction[Vector[String], NoStream, Read] = {
    for {
      catalogs <- DatabaseMeta.getCatalogs
    } yield catalogs
  }

  type ResultTransform     = Function[List[DataItem], ResolverResult]
  type ResultListTransform = Function[List[ScalarListValue], ResolverResult]

}
