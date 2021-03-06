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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.base.BaseTemplatesTests
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager
import ujson.Str

class CatApiTests extends WordSpec with BaseTemplatesTests {

  override lazy val rorContainer: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/cat_api/readonlyrest.yml"
  )

  private lazy val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev1", "test"))
  private lazy val dev2ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
  private lazy val dev3ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev3", "test"))

  "A _cat/indices API" when {
    "return empty indices" when {
      "there is no index in ES" in {
        val indices = dev1ClusterStateManager.catIndices()

        indices.responseCode should be(200)
        indices.results.size should be (0)
      }
      "dev1 has no indices" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices()

        indices.responseCode should be(200)
        indices.results.size should be (0)
      }
    }
    "return only dev1 indices" when {
      "request is related to all indices" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices()

        indices.responseCode should be(200)
        indices.results.size should be (1)
        indices.results(0)("index") should be (Str("dev1_index"))
      }
      "request is related to one index" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices("dev1_index")

        indices.responseCode should be(200)
        indices.results.size should be (1)
        indices.results(0)("index") should be (Str("dev1_index"))
      }
      "request index has wildcard" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices("dev*")

        indices.responseCode should be(200)
        indices.results.size should be (1)
        indices.results(0)("index") should be (Str("dev1_index"))
      }
    }
    "return all indices" when {
      "user 3 is asking for them (his block doesn't contain `indices` rule)" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev1_index")
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev3ClusterStateManager.catIndices()

        indices.responseCode should be(200)
        indices.results.size should be (2)
        indices.results(0)("index") should be (Str("dev1_index"))
        indices.results(1)("index") should be (Str("dev2_index"))
      }
    }
    "return forbidden" when {
      "user has no access to given index" in {
        createIndexWithExampleDoc(adminDocumentManager, "dev2_index")

        val indices = dev1ClusterStateManager.catIndices("dev2_index")

        indices.responseCode should be(401)
      }
      "user is trying to access non-existent index" in {
        val indices = dev1ClusterStateManager.catIndices("non-existent")

        indices.responseCode should be(404)
      }
    }
  }

  "A _cat/template API" when {
    "user is dev1" should {
      "be allowed to get all templates using /_cat/templates API" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates()

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
      }
      "be allowed to get specific template using /_cat/templates API" when {
        "there is no template at all" in {
          val templates = dev1ClusterStateManager.catTemplates()

          templates.responseCode should be(200)
          templates.results.size should be (0)
        }
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (1)
              templates.results(0)("name") should be (Str("temp1"))
            }
          }
        }
      }
    }
    "user is dev2" should {
      "not be able to get templates using /_cat/templates API" when {
        "there are no his templates but other user's one exists" when {
          "there is no index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

                val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
                val templates = dev1ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
            }
          }
          "there is an index defined for it" when {
            "template has index pattern with wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
            }
            "template has index pattern with no wildcard" when {
              "rule has index pattern with wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
                createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
              "rule has index pattern with no wildcard" in {
                adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
                createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

                val templates = dev2ClusterStateManager.catTemplates()

                templates.responseCode should be(200)
                templates.results.size should be (0)
              }
            }
          }
        }
      }
      "not be able to get specific, foreign template using /_cat/templates API" when {
        "there is no index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
          }
        }
        "there is an index defined for it" when {
          "template has index pattern with wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_*"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
          }
          "template has index pattern with no wildcard" when {
            "rule has index pattern with wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("custom_dev1_index_test"))
              createIndexWithExampleDoc(adminDocumentManager, "custom_dev1_index_test")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
            "rule has index pattern with no wildcard" in {
              adminTemplateManager.insertTemplateAndWaitForIndexing("temp1", templateExample("dev1_index"))
              createIndexWithExampleDoc(adminDocumentManager, "dev1_index")

              val dev1ClusterStateManager = new ClusterStateManager(rorContainer.nodesContainers.head.client("dev2", "test"))
              val templates = dev1ClusterStateManager.catTemplates("temp1")

              templates.responseCode should be(200)
              templates.results.size should be (0)
            }
          }
        }
      }
    }
  }
}
