/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.integration

import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterEach, WordSpec}
import tech.beshu.ror.integration.AdminApiTests.{insertInIndexConfig, removeConfigIndex}
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster}
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, DocumentManagerJ, IndexManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

class AdminApiTests extends WordSpec with ForAllTestContainer with BeforeAndAfterEach {

  private val rorWithIndexConfig = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/admin_api/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(
      numberOfInstances = 2,
      nodeDataInitializer = AdminApiTests.nodeDataInitializer()
    )
  )

  private val rorWithNoIndexConfig = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR2",
    rorConfigFileName = "/admin_api/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(configHotReloadingEnabled = false)
  )

  override val container: MultipleContainers = MultipleContainers(rorWithIndexConfig, rorWithNoIndexConfig)

  private lazy val ror1WithIndexConfigAdminActionManager = new ActionManagerJ(rorWithIndexConfig.nodesContainers.head.adminClient)
  private lazy val rorWithNoIndexConfigAdminActionManager = new ActionManagerJ(rorWithNoIndexConfig.nodesContainers.head.adminClient)

  "An admin REST API" should {
    "provide a method for force refresh ROR config" which {
      "is going to reload ROR core" when {
        "in-index config is newer than current one" in {
          insertInIndexConfig(
            new DocumentManagerJ(rorWithNoIndexConfig.nodesContainers.head.adminClient),
            "/admin_api/readonlyrest_index.yml"
          )

          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ok")
          result.getResponseJsonMap.get("message") should be("ReadonlyREST settings were reloaded with success!")
        }
      }
      "return info that config is up to date" when {
        "in-index config is the same as current one" in {
          insertInIndexConfig(
            new DocumentManagerJ(rorWithNoIndexConfig.nodesContainers.head.adminClient),
            "/admin_api/readonlyrest.yml"
          )

          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Current settings are already loaded")
        }
      }
      "return info that in-index config does not exist" when {
        "there is no in-index settings configured yet" in {
          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Cannot find settings index")
        }
      }
      "return info that cannot reload config" when {
        "config cannot be reloaded (eg. because LDAP is not achievable)" in {
          insertInIndexConfig(
            new DocumentManagerJ(rorWithNoIndexConfig.nodesContainers.head.adminClient),
            "/admin_api/readonlyrest_with_ldap.yml"
          )

          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for update in-index config" which {
      "is going to reload ROR core and store new in-index config" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String) = {
            val result = ror1WithIndexConfigAdminActionManager.actionPost(
              "_readonlyrest/admin/config",
              s"""{"settings": "${escapeJava(getResourceContent(rorSettingsResource))}"}"""
            )
            result.getResponseCode should be(200)
            result.getResponseJsonMap.get("status") should be("ok")
            result.getResponseJsonMap.get("message") should be("updated settings")  
          }
          
          val dev1Ror1stInstanceSearchManager = new SearchManager(rorWithIndexConfig.nodesContainers.head.client("dev1", "test"))
          val dev2Ror1stInstanceSearchManager = new SearchManager(rorWithIndexConfig.nodesContainers.head.client("dev2", "test"))
          val dev1Ror2ndInstanceSearchManager = new SearchManager(rorWithIndexConfig.nodesContainers.tail.head.client("dev1", "test"))
          val dev2Ror2ndInstanceSearchManager = new SearchManager(rorWithIndexConfig.nodesContainers.tail.head.client("dev2", "test"))

          // before first reload no user can access indices 
          val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("/test1_index/_search")
          dev1ror1Results.responseCode should be (401)
          val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("/test2_index/_search")
          dev2ror1Results.responseCode should be (401)
          val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("/test1_index/_search")
          dev1ror2Results.responseCode should be (401)
          val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("/test2_index/_search")
          dev2ror2Results.responseCode should be (401)
          
          // first reload
          forceReload("/admin_api/readonlyrest_first_update.yml")

          // after first reload only dev1 can access indices
          Thread.sleep(7000) // have to wait for ROR1_2 instance config reload
          val dev1ror1After1stReloadResults = dev1Ror1stInstanceSearchManager.search("/test1_index/_search")
          dev1ror1After1stReloadResults.responseCode should be (200)
          val dev2ror1After1stReloadResults = dev2Ror1stInstanceSearchManager.search("/test2_index/_search")
          dev2ror1After1stReloadResults.responseCode should be (401)
          val dev1ror2After1stReloadResults = dev1Ror2ndInstanceSearchManager.search("/test1_index/_search")
          dev1ror2After1stReloadResults.responseCode should be (200)
          val dev2ror2After1stReloadResults = dev2Ror2ndInstanceSearchManager.search("/test2_index/_search")
          dev2ror2After1stReloadResults.responseCode should be (401)

          // second reload
          forceReload("/admin_api/readonlyrest_second_update.yml")

          // after second reload dev1 & dev2 can access indices
          Thread.sleep(7000) // have to wait for ROR1_2 instance config reload
          val dev1ror1After2ndReloadResults = dev1Ror1stInstanceSearchManager.search("/test1_index/_search")
          dev1ror1After2ndReloadResults.responseCode should be (200)
          val dev2ror1After2ndReloadResults = dev2Ror1stInstanceSearchManager.search("/test2_index/_search")
          dev2ror1After2ndReloadResults.responseCode should be (200)
          val dev1ror2After2ndReloadResults = dev1Ror2ndInstanceSearchManager.search("/test1_index/_search")
          dev1ror2After2ndReloadResults.responseCode should be (200)
          val dev2ror2After2ndReloadResults = dev2Ror2ndInstanceSearchManager.search("/test2_index/_search")
          dev2ror2After2ndReloadResults.responseCode should be (200)

        }
      }
      "return info that config is up to date" when {
        "in-index config is the same as provided one" in {
          val result = ror1WithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Current settings are already loaded")
        }
      }
      "return info that config is malformed" when {
        "invalid JSON is provided" in {
          val result = ror1WithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"${escapeJava(getResourceContent("/admin_api/readonlyrest_first_update.yml"))}"
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("JSON body malformed")
        }
      }
      "return info that cannot reload" when {
        "ROR core cannot be reloaded" in {
          val result = ror1WithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))}"}"""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for fetching current in-index config" which {
      "return current config" when {
        "there is one in index" in {
          val getIndexConfigResult = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config")
          getIndexConfigResult.getResponseCode should be(200)
          getIndexConfigResult.getResponseJsonMap.get("status") should be("ok")
          getIndexConfigResult.getResponseJsonMap.get("message").asInstanceOf[String] should be {
            getResourceContent("/admin_api/readonlyrest_index.yml")
          }
        }
      }
      "return info that there is no in-index config" when {
        "there is none in index" in {
          val getIndexConfigResult = rorWithNoIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config")
          getIndexConfigResult.getResponseCode should be(200)
          getIndexConfigResult.getResponseJsonMap.get("status") should be("empty")
          getIndexConfigResult.getResponseJsonMap.get("message").asInstanceOf[String] should be {
            "Cannot find settings index"
          }
        }
      }
    }
    "provide a method for fetching current file config" which {
      "return current config" in {
        val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/file")
        result.getResponseCode should be(200)
        result.getResponseJsonMap.get("status") should be("ok")
        result.getResponseJsonMap.get("message").asInstanceOf[String] should be {
          getResourceContent("/admin_api/readonlyrest.yml")
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    // back to configuration loaded on container start
    rorWithNoIndexConfigAdminActionManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest.yml"))}"}"""
    )
    removeConfigIndex(new IndexManagerJ(rorWithNoIndexConfig.nodesContainers.head.adminClient))

    ror1WithIndexConfigAdminActionManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
    )
  }
}

object AdminApiTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    insertInIndexConfig(documentManager, "/admin_api/readonlyrest_index.yml")
  }

  private def insertInIndexConfig(documentManager: DocumentManagerJ, resourceFilePath: String): Unit = {
    documentManager.insertDocAndWaitForRefresh(
      "/.readonlyrest/settings/1",
      s"""{"settings": "${escapeJava(getResourceContent(resourceFilePath))}"}"""
    )
  }

  private def removeConfigIndex(indexManager: IndexManagerJ): Unit = {
    indexManager.remove(".readonlyrest")
  }

}