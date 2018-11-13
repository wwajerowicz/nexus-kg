package ch.epfl.bluebrain.nexus.kg.indexing

import java.util.regex.Pattern.quote

import akka.actor.{ActorRef, ActorSystem}
import akka.kafka.ConsumerSettings
import akka.stream.ActorMaterializer
import ch.epfl.bluebrain.nexus.admin.client.types.KafkaEvent._
import ch.epfl.bluebrain.nexus.admin.client.types.{Account, KafkaEvent, Project}
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.commons.test.Resources._
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinator.Msg
import ch.epfl.bluebrain.nexus.kg.async.{DistributedCache, ProjectViewCoordinator}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.directives.LabeledProject
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticView, SingleView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.indexing.v0.MigrationIndexer
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.service.kafka.KafkaConsumer
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.jena.query.ResultSet
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.Future

// $COVERAGE-OFF$
private class Indexing(resources: Resources[Task], cache: DistributedCache[Task], coordinator: ActorRef)(
    implicit as: ActorSystem,
    config: AppConfig) {

  private val consumerSettings = ConsumerSettings(as, new StringDeserializer, new StringDeserializer)

  private val elasticUUID = "684bd815-9273-46f4-ac1c-0383d4a98254"
  private val sparqlUUID  = "d88b71d2-b8a4-4744-bf22-2d99ef5bd26b"

  private val defaultEsMapping =
    jsonContentOf("/elastic/mapping.json", Map(quote("{{docType}}") -> config.elastic.docType))

  def startKafkaStream(): Unit = {

    // format: off
    def defaultEsView(projectRef: ProjectRef): ElasticView =
      ElasticView(defaultEsMapping, Set.empty, None, includeMetadata = true, sourceAsText = true, projectRef, nxv.defaultElasticIndex.value, elasticUUID, 1L, deprecated = false)
    // format: on

    def defaultSparqlView(projectRef: ProjectRef): SparqlView =
      SparqlView(projectRef, nxv.defaultSparqlIndex.value, sparqlUUID, 1L, deprecated = false)

    def defaultInProjectResolver(projectRef: ProjectRef): InProjectResolver =
      InProjectResolver(projectRef, nxv.InProject.value, 1L, deprecated = false, 1)

    def index(event: KafkaEvent): Future[Unit] = {
      val update = event match {
        case OrganizationCreated(_, label, uuid, rev, _, org) =>
          cache.addAccount(AccountRef(uuid), Account(org.name, rev, label, deprecated = false, uuid))

        case OrganizationUpdated(_, label, uuid, rev, _, org) =>
          cache.addAccount(AccountRef(uuid), Account(org.name, rev, label, deprecated = false, uuid))

        case OrganizationDeprecated(_, uuid, rev, _) =>
          cache.deprecateAccount(AccountRef(uuid), rev)

        case ProjectCreated(_, label, uuid, orgUUid, rev, _, proj) =>
          val projectRef = ProjectRef(uuid)
          val accountRef = AccountRef(orgUUid)
          val project    = Project(proj.name, label, proj.prefixMappings, proj.base, rev, deprecated = false, uuid)
          for {
            _ <- cache.addProject(projectRef, accountRef, project)
            _ <- cache.addResolver(projectRef, defaultInProjectResolver(projectRef))
            _ <- cache.addView(ProjectRef(uuid), defaultEsView(projectRef))
            _ <- cache.addView(ProjectRef(uuid), defaultSparqlView(projectRef))
            _ = coordinator ! Msg(AccountRef(orgUUid), ProjectRef(uuid))
          } yield ()

        case ProjectUpdated(_, label, uuid, orgUUid, rev, _, proj) =>
          val projectRef = ProjectRef(uuid)
          val accountRef = AccountRef(orgUUid)
          val project    = Project(proj.name, label, proj.prefixMappings, proj.base, rev, deprecated = false, uuid)
          cache.addProject(projectRef, accountRef, project)

        case ProjectDeprecated(_, uuid, orgUUid, rev, _) =>
          cache.deprecateProject(ProjectRef(uuid), AccountRef(orgUUid), rev)
      }
      update.runAsync
    }

    KafkaConsumer.start(consumerSettings, index, config.kafka.adminTopic, "admin-events", committable = false, None)
    ()
  }

  def startResolverStream(): Unit = {
    ResolverIndexer.start(resources, cache)
    ()
  }

  def startViewStream(): Unit = {
    ViewIndexer.start(resources, cache)
    ()
  }

  def startMigrationStream(): Unit = {
    val migration = config.kafka.migration
    if (migration.enabled) {
      MigrationIndexer.start(resources.repo, migration)
    }
  }
}

object Indexing {

  /**
    * Starts all indexing streams:
    * <ul>
    *   <li>Views</li>
    *   <li>Projects</li>
    *   <li>Accounts</li>
    *   <li>Resolvers</li>
    * </ul>
    *
    * @param resources the resources operations
    * @param cache     the distributed cache
    */
  def start(resources: Resources[Task], cache: DistributedCache[Task])(implicit as: ActorSystem,
                                                                       ucl: HttpClient[Task, ResultSet],
                                                                       config: AppConfig): Unit = {

    implicit val mt            = ActorMaterializer()
    implicit val ul            = HttpClient.taskHttpClient
    implicit val jsonClient    = HttpClient.withTaskUnmarshaller[Json]
    implicit val elasticClient = ElasticClient[Task](config.elastic.base)

    def selector(view: SingleView, labeledProject: LabeledProject): ActorRef = view match {
      case v: ElasticView => ElasticIndexer.start(v, resources, labeledProject)
      case v: SparqlView  => SparqlIndexer.start(v, resources, labeledProject)
    }

    def onStop(view: SingleView): Task[Boolean] = view match {
      case v: ElasticView =>
        elasticClient.deleteIndex(v.index)
      case _: SparqlView =>
        BlazegraphClient[Task](config.sparql.base, view.name, config.sparql.akkaCredentials).deleteNamespace
    }

    val coordinator = ProjectViewCoordinator.start(cache, selector, onStop, None, config.cluster.shards)
    val indexing    = new Indexing(resources, cache, coordinator)
    indexing.startKafkaStream()
    indexing.startResolverStream()
    indexing.startViewStream()
    indexing.startMigrationStream()
  }

}
// $COVERAGE-ON$
