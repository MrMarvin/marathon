package mesosphere.util.state.zk

import java.util.concurrent.TimeUnit

import com.twitter.util.Await
import com.twitter.zk.ZkClient
import mesosphere.marathon.integration.setup.StartedZookeeper
import mesosphere.util.state.PersistentStoreTest
import mesosphere.util.state.mesos.MesosStateStore
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.ZooDefs.Ids
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import ZKStore._

class ZKStoreTest extends PersistentStoreTest with StartedZookeeper with Matchers {

  //
  // See PersistentStoreTests for general store tests
  //

  test("Create nested root path") {
    val store = new ZKStore(persistentStore.client, persistentStore.client("/some/nested/path"))
  }

  test("Compatibility to mesos state storage. Write zk read mesos.") {
    val created = persistentStore.create("foo", "Hello".getBytes).futureValue
    val mesosLoaded = mesosStore.load("foo").futureValue
    mesosLoaded should be('defined)
    mesosLoaded.get.bytes should be(created.bytes)

    persistentStore.update(created.withNewContent("Hello again".getBytes)).futureValue
    val mesosLoadUpdated = mesosStore.load("foo").futureValue
    mesosLoadUpdated should be('defined)
    mesosLoadUpdated.get.bytes should be("Hello again".getBytes)
  }

  test("Compatibility to mesos state storage. Write mesos read zk.") {
    val created = mesosStore.create("foo", "Hello".getBytes).futureValue
    val zkLoaded = persistentStore.load("foo").futureValue
    zkLoaded should be('defined)
    zkLoaded.get.bytes should be(created.bytes)

    mesosStore.update(created.withNewContent("Hello again".getBytes)).futureValue
    val zkLoadUpdated = persistentStore.load("foo").futureValue
    zkLoadUpdated should be('defined)
    zkLoadUpdated.get.bytes should be("Hello again".getBytes)
  }

  test("Deeply nested paths are created") {
    val client = persistentStore.client
    val path = client("/s/o/m/e/d/e/e/p/ly/n/e/s/t/e/d/p/a/t/h")
    val store = new ZKStore(client, path)
    path.exists().asScala.failed.futureValue shouldBe a[NoNodeException]
    store.initialize().futureValue
    path.exists().asScala.futureValue.stat.getVersion should be(0)
  }

  test("Already existing paths are not created") {
    val client = persistentStore.client
    val path = client("/some/deeply/nested/path")
    path.exists().asScala.failed.futureValue shouldBe a[NoNodeException]
    new ZKStore(client, path).initialize().futureValue
    path.exists().asScala.futureValue.stat.getVersion should be(0)
    new ZKStore(client, path).initialize().futureValue
    path.exists().asScala.futureValue.stat.getVersion should be(0)
  }

  lazy val persistentStore: ZKStore = {
    implicit val timer = com.twitter.util.Timer.Nil
    val timeout = com.twitter.util.TimeConversions.intToTimeableNumber(10).minutes
    val client = ZkClient(config.zkHostAndPort, timeout).withAcl(Ids.OPEN_ACL_UNSAFE.asScala)
    new ZKStore(client, client(config.zkPath))
  }

  lazy val mesosStore: MesosStateStore = {
    val duration = 30.seconds
    val state = new ZooKeeperState(
      config.zkHostAndPort,
      duration.toMillis,
      TimeUnit.MILLISECONDS,
      config.zkPath
    )
    new MesosStateStore(state, duration)
  }
  override protected def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap + ("zkPort" -> "2185"))
  }

  override protected def afterAll(configMap: ConfigMap): Unit = {
    Await.ready(persistentStore.client.release())
    super.afterAll(configMap)
  }
}
