package ch.epfl.bluebrain.nexus.kg.directives

import java.net.URLEncoder
import java.nio.file.Paths
import java.time.Instant

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaRanges._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.commons.search.Sort.OrderType._
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination, SearchAfterPagination, Sort, SortList}
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.cache.StorageCache
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Contexts.errorCtxUri
import ch.epfl.bluebrain.nexus.kg.config.{Schemas, Settings}
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.{OutputFormat, SearchParams}
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat._
import ch.epfl.bluebrain.nexus.kg.routes.Routes.{exceptionHandler, rejectionHandler}
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.kg.storage.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.kg.storage.StorageEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import io.circe.Json
import io.circe.generic.auto._
import monix.eval.Task
import org.mockito.{IdiomaticMockito, Mockito}
import org.mockito.Mockito._
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class QueryDirectivesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with EitherValues
    with TestHelper
    with IdiomaticMockito
    with BeforeAndAfter {

  private implicit val storageCache: StorageCache[Task] = mock[StorageCache[Task]]
  private val appConfig                                 = Settings(system).appConfig

  before {
    Mockito.reset(storageCache)
  }

  "A query directive" when {

    implicit val config = PaginationConfig(10, 50, 10000)
    implicit val storageConfig = appConfig.storage.copy(
      disk = DiskStorageConfig(Paths.get("/tmp/"), "SHA-256", read, write, false, 1024L),
      remoteDisk = RemoteDiskStorageConfig("http://example.com", "v1", None, "SHA-256", read, write, true, 1024L),
      amazon = S3StorageConfig("MD5", read, write, true, 1024L),
      password = "password",
      salt = "salt",
      fileAttrRetry = RetryStrategyConfig("linear", 300.millis, 5.minutes, 100, 1.second)
    )

    implicit def paginationMarshaller(
        implicit m1: ToEntityMarshaller[FromPagination],
        m2: ToEntityMarshaller[SearchAfterPagination]
    ): ToEntityMarshaller[Pagination] =
      Marshaller { _ =>
        {
          case f: FromPagination        => m1(f)
          case s: SearchAfterPagination => m2(s)
        }
      }

    // format: off
    def genProject() =
      Project(genIri, "project", "organization", None, url"${nxv.projects.value.asString}/", url"${genIri}/", Map("nxv" -> nxv.base), genUUID, genUUID, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
    // format: on

    def routePagination(): Route =
      (get & paginated) { page =>
        complete(StatusCodes.OK -> page)
      }

    def routeFormat(strict: Boolean, default: OutputFormat): Route =
      (get & outputFormat(strict, default)) { output =>
        complete(StatusCodes.OK -> output.toString)
      }

    def routeStorage(implicit project: Project): Route =
      handleExceptions(exceptionHandler) {
        handleRejections(rejectionHandler) {
          (get & storage) { st =>
            complete(StatusCodes.OK -> st.asGraph.toJson().rightValue)
          }
        }
      }

    def routeSearchParams(implicit project: Project): Route =
      handleExceptions(exceptionHandler) {
        handleRejections(rejectionHandler) {
          (get & searchParams) { params =>
            complete(StatusCodes.OK -> params)
          }
        }
      }

    "dealing with pagination" should {

      "return default values when no query parameters found" in {
        Get("/") ~> routePagination() ~> check {
          responseAs[FromPagination] shouldEqual Pagination(config.defaultSize)
        }
      }

      "return pagination from query parameters" in {
        Get("/some?from=1&size=20") ~> routePagination() ~> check {
          responseAs[FromPagination] shouldEqual Pagination(1, 20)
        }
      }

      "return default parameters when the query params are under the minimum" in {
        Get("/some?from=-1&size=-1") ~> routePagination() ~> check {
          responseAs[FromPagination] shouldEqual Pagination(0, 1)
        }
      }

      "return maximum size when size is over the maximum" in {
        Get("/some?size=500") ~> routePagination() ~> check {
          responseAs[FromPagination] shouldEqual Pagination(0, config.sizeLimit)
        }
      }

      "throw error when after is not a valid JSON" in {
        Get("/some?after=notJson") ~> routePagination() ~> check {
          rejection shouldBe a[MalformedQueryParamRejection]
        }
      }

      "parse search after parameter" in {
        val after = Json.arr(Json.fromString(Instant.now().toString))
        Get(s"/some?after=${URLEncoder.encode(after.noSpaces, "UTF-8")}") ~> routePagination() ~> check {
          responseAs[SearchAfterPagination] shouldEqual Pagination(after, config.defaultSize)
        }
      }

      "reject when both from and after are present" in {
        val after = Json.arr(Json.fromString(Instant.now().toString))
        Get(s"/some?from=10&after=${URLEncoder.encode(after.noSpaces, "UTF-8")}") ~> routePagination() ~> check {
          rejection shouldBe a[MalformedQueryParamRejection]
        }
      }

      "reject when from is bigger than maximum" in {
        Get("/some?from=10001") ~> routePagination() ~> check {
          rejection shouldBe a[MalformedQueryParamRejection]
        }
      }
    }

    "dealing with output format" should {

      "return jsonLD format from Accept header and query params. on strict mode" in {
        Get("/some?format=compacted") ~> Accept(`application/json`) ~> routeFormat(strict = true, Compacted) ~> check {
          responseAs[String] shouldEqual "Compacted"
        }
        Get("/some?format=expanded") ~> Accept(`application/json`) ~> routeFormat(strict = true, Compacted) ~> check {
          responseAs[String] shouldEqual "Expanded"
        }
      }

      "ignore query param. and return default format when Accept header does not match on strict mode" in {
        Get("/some?format=expanded") ~> Accept(`application/*`) ~> routeFormat(strict = true, Binary) ~> check {
          responseAs[String] shouldEqual "Binary"
        }
        Get("/some?format=compacted") ~> Accept(`application/*`, `*/*`) ~> routeFormat(strict = true, DOT) ~> check {
          responseAs[String] shouldEqual "DOT"
        }
      }

      "return the format from the closest Accept header match and the query param" in {
        Get("/some?format=expanded") ~> Accept(`application/*`) ~> routeFormat(strict = false, Binary) ~> check {
          responseAs[String] shouldEqual "Expanded"
        }
        Get("/some") ~> Accept(`application/n-triples`, `*/*`) ~> routeFormat(strict = false, Binary) ~> check {
          responseAs[String] shouldEqual "Triples"
        }

        Get("/some") ~> Accept(`text/*`, `*/*`) ~> routeFormat(strict = false, Binary) ~> check {
          responseAs[String] shouldEqual "DOT"
        }

        Get("/some?format=compacted") ~> Accept(
          `application/javascript`,
          DOT.contentType.mediaType,
          `application/n-triples`,
          `*/*`
        ) ~> routeFormat(strict = false, Binary) ~> check {
          responseAs[String] shouldEqual "DOT"
        }
      }
    }

    "dealing with storages" should {

      "return the storage when specified as a query parameter" in {
        implicit val project = genProject()
        val storage: Storage = DiskStorage.default(project.ref)
        when(storageCache.get(project.ref, nxv.withSuffix("mystorage").value)).thenReturn(Task(Some(storage)))
        Get("/some?storage=nxv:mystorage") ~> routeStorage ~> check {
          responseAs[Json] shouldEqual storage.asGraph.toJson().rightValue
        }
      }

      "return the default storage" in {
        implicit val project = genProject()
        val storage: Storage = DiskStorage.default(project.ref)
        when(storageCache.getDefault(project.ref)).thenReturn(Task(Some(storage)))
        Get("/some") ~> routeStorage ~> check {
          responseAs[Json] shouldEqual storage.asGraph.toJson().rightValue
        }
      }

      "return no storage when does not exists on the cache" in {
        implicit val project = genProject()
        when(storageCache.getDefault(project.ref)).thenReturn(Task(None))
        Get("/some") ~> routeStorage ~> check {
          status shouldEqual StatusCodes.NotFound
        }
      }
    }

    "dealing with search parameters" should {

      "reject when sort and q are simultaneously present" in {
        implicit val project = genProject()
        Get("/some?q=something&sort=@id") ~> routeSearchParams ~> check {
          responseAs[Json] shouldEqual
            Json
              .obj(
                "@type" -> Json.fromString("MalformedQueryParam"),
                "reason" -> Json.fromString(
                  "The query parameter 'sort' was malformed: 'Should be omitted when 'q' parameter is present'."
                )
              )
              .addContext(errorCtxUri)
        }
      }

      "return a SearchParams" in {
        implicit val project    = genProject()
        val schema: AbsoluteIri = Schemas.resolverSchemaUri
        Get(
          s"/some?deprecated=true&rev=2&createdBy=nxv:user&updatedBy=batman&type=A&type=B&schema=${schema.asString}&q=Some%20text"
        ) ~> routeSearchParams ~> check {
          val expected = SearchParams(
            deprecated = Some(true),
            rev = Some(2),
            schema = Some(schema),
            createdBy = Some(nxv.withSuffix("user").value),
            updatedBy = Some(project.base + "batman"),
            types = List(project.vocab + "A", project.vocab + "B"),
            q = Some("some text")
          )
          val expected2 = expected.copy(types = List(project.vocab + "B", project.vocab + "A"))

          responseAs[SearchParams] should (be(expected) or be(expected2))
        }
      }

      "return a SearchParam with custom sorting" in {
        implicit val project = genProject()
        Get(s"/some?sort=_createdBy&sort=-_updatedBy&sort=@id&sort=-@type") ~> routeSearchParams ~> check {
          val expected = SearchParams(
            sort = SortList(
              List(Sort(nxv.createdBy.prefix), Sort(Desc, nxv.updatedBy.prefix), Sort("@id"), Sort(Desc, "@type"))
            )
          )

          responseAs[SearchParams] shouldEqual expected
        }
      }
    }
  }
}
