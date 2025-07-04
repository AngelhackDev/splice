package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_OffboardSv,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  CometBftJsonRpcRequest,
  CometBftJsonRpcRequestId,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.cometbft.{CometBftConnectionConfig, CometBftHttpRpcClient}
import org.lfdecentralizedtrust.splice.sv.config.{CometBftGovernanceKey, SvCometBftConfig}
import org.lfdecentralizedtrust.splice.util.SvTestUtil
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import io.circe.Json
import monocle.Monocle.toAppliedFocusOps

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SvCometBftIntegrationTest extends IntegrationTestWithSharedEnvironment with SvTestUtil {

  import ExecutionContext.Implicits.global

  // generated with `scripts/generate-cometbft-governance-keys.sh`
  val sv3CometBftGovernanceKey = CometBftGovernanceKey(
    "vTrMu0ocEHttI2YaoLeqLZPdHkBpjf8syW1o2rneeVk=",
    "feEYBquDbB7YYNBUOTOSlxWAJ2m23RD05Fp0PTtR0gM=",
  )
  val sv4CometBftGovernanceKey = CometBftGovernanceKey(
    "tYd7aUPmSBqdqMP1Ogs3SvBpu6kR8+pH381D8wZQDhE=",
    "wDhQgIS+ZrHfxNk0YxbUXSWsUBlzUt8p2C+xO1BTHmg=",
  )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) =>
          ConfigTransforms.updateAllSvAppConfigs { (name, config) =>
            {
              val svIdx = name.replace("sv", "")
              config
                .focus(_.cometBftConfig)
                .replace(
                  Some(
                    SvCometBftConfig(
                      enabled = true,
                      connectionUri = s"http://127.0.0.1:266${svIdx}7",
                      governanceKey = if (name == "sv4") Some(sv4CometBftGovernanceKey) else None,
                    )
                  )
                )
                .focus(_.automation.enableCometbftReconciliation)
                .replace(true)
            }
          }(config),
        (_, config) =>
          config.copy(
            svApps = config.svApps +
              (InstanceName.tryCreate("sv2Local") -> {
                config
                  .svApps(InstanceName.tryCreate("sv2"))
                  .focus(_.cometBftConfig)
                  .modify(_.map(_.focus(_.connectionUri).replace(s"http://127.0.0.1:26657")))
              }) +
              (InstanceName.tryCreate("sv3Local") -> {
                config
                  .svApps(InstanceName.tryCreate("sv3"))
                  .focus(_.cometBftConfig)
                  .modify(_.map(_.focus(_.governanceKey).replace(Some(sv3CometBftGovernanceKey))))
              })
          ),
      )
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()
      .withManualStart

  "all nodes become validators" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend, sv2Backend, sv3Backend, sv4Backend)
    forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { sv =>
      eventually(timeUntilSuccess = 2.minute) {
        withClue(s"CometBFT node for ${sv.name} becomes a validator") {
          cometBFTnodeIsUpToDateValidator(sv)
        }
      }
    }
  }

  "sv4 uses a governance key from config" in { implicit env =>
    getCurrentGovernanceKey(sv4Backend) should be(sv4CometBftGovernanceKey.publicKey)
  }

  "sv3 can switch to a new governance key set in its config" in { implicit env =>
    getCurrentGovernanceKey(sv3Backend) should not be sv3CometBftGovernanceKey.publicKey
    sv3Backend.stop()
    sv3LocalBackend.startSync()
    eventually() {
      getCurrentGovernanceKey(sv3Backend) should be(sv3CometBftGovernanceKey.publicKey)
    }
  }

  "sv2 can reonboard a different cometbft node" in { implicit env =>
    def getValidatorAddresses() =
      sv1Backend
        .cometBftNodeDump()
        .validators
        .hcursor
        .downField("validators")
        .as[Seq[io.circe.Json]]
        .value
        .map(_.hcursor.downField("address").as[String].value)

    val prevAddresses = getValidatorAddresses()
    prevAddresses should have size 4
    sv2Backend.stop()
    sv2LocalBackend.startSync()
    eventually() {
      val newAddresses = getValidatorAddresses()
      prevAddresses should not be newAddresses
      newAddresses should have size 4
    }
  }

  "removed SV has its node removed" in { implicit env =>
    eventually(timeUntilSuccess = 2.minute) {
      sv4Backend.cometBftNodeStatus().votingPower.doubleValue should be(1d)
    }
    val action: ActionRequiringConfirmation =
      new ARC_DsoRules(
        new SRARC_OffboardSv(
          new DsoRules_OffboardSv(sv4Backend.getDsoInfo().svParty.toProtoPrimitive)
        )
      )
    sv4Backend.stop()
    sv1Backend.createVoteRequest(
      sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
      action,
      "url",
      "description",
      // The offboarding becomes effective after the timeout set here, unless we have SV4 vote as well.
      new RelTime(30_000_000),
      None,
    )
    val trackingCid = sv1Backend.getLatestVoteRequestTrackingCid()
    Seq(sv2Backend, sv3Backend).foreach { sv =>
      eventually() {
        sv.listVoteRequests() should not be empty
        sv.castVote(trackingCid, isAccepted = true, "url", "description")
      }
    }
    eventually(timeUntilSuccess = 2.minute, maxPollInterval = 1.second) {
      cometbftClientForSvApp(sv4Backend)
        .nodeStatus()
        .valueOrFail("sv4 node status")
        .futureValue
        .validatorInfo
        .votingPower
        .toDouble should be(0d)
    }
    // Wait until the namespace change goes through, otherwise the namespace reset trigger might break.
    eventually() {
      Seq(sv1Backend, sv2Backend, sv3Backend).foreach { sv =>
        sv.participantClient.topology.decentralized_namespaces
          .list(
            store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
            filterNamespace = dsoParty.uid.namespace.toProtoPrimitive,
          )
          .loneElement
          .item
          .owners
          .forgetNE should have size 3
      }
    }
  }

  // In case this test fails, please also test CometBFT state sync is working by:
  // - deploying a scratchnet cluster
  // - waiting a while for the block height to grow beyond the minTrustHeightAge configured in the cometbft helm chart
  // - deploying the SV runbook
  "Sv app" should {
    "expose CometBFT RPC methods required for state sync" in { implicit env =>
      testJsonRpcCall(1, "status", Map.empty, Seq("node_info", "sync_info", "validator_info"))
      testJsonRpcCall(2, "block", Map("height" -> Json.fromString("1")), Seq("block_id", "block"))
      testJsonRpcCall(3, "commit", Map("height" -> Json.fromString("1")), Seq("signed_header"))
      testJsonRpcCall(
        4,
        "validators",
        Map("height" -> Json.fromString("1")),
        Seq("block_height", "validators"),
      )
      testJsonRpcCall(
        5,
        "consensus_params",
        Map("height" -> Json.fromString("1")),
        Seq("block_height", "consensus_params"),
      )
      // not required for state sync but to aid in debugging CometBFT issues
      testJsonRpcCall(6, "consensus_state", Map.empty, Seq("round_state"))
    }
  }

  private def cometBFTnodeIsUpToDateValidator(sv: SvAppBackendReference) = {
    // node is up to date
    sv.cometBftNodeStatus().catchingUp shouldBe false
    // validate dump
    sv.cometBftNodeDump().abciInfo.isObject shouldBe true
    sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
  }

  private def cometbftClientForSvApp(sv: SvAppBackendReference) = {
    new CometBftHttpRpcClient(
      CometBftConnectionConfig(sv.config.cometBftConfig.value.connectionUri),
      NamedLoggerFactory.root,
    )
  }

  private def testJsonRpcCall(
      id: Long,
      method: String,
      params: Map[String, Json],
      responseKeys: Seq[String],
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val id_ = CometBftJsonRpcRequestId.fromNested2(id)
    val method_ = CometBftJsonRpcRequest.Method.from(method).value
    val response = sv1Backend.cometBftJsonRpcRequest(id_, method_, params)
    response.id shouldBe id_
    response.jsonrpc shouldBe "2.0"
    responseKeys.foreach(key => response.result.findAllByKey(key) should not be empty)
  }

  private def getCurrentGovernanceKey(sv: SvAppBackendReference) = {
    val dsoInfo = sv.getDsoInfo()
    val svParty = dsoInfo.svParty
    val keys = dsoInfo.svNodeStates(svParty).payload.state.synchronizerNodes.asScala.flatMap {
      case (_, node) =>
        node.cometBft.governanceKeys.asScala.map(_.pubKey)
    }
    keys should have size 1
    keys.head
  }
}
