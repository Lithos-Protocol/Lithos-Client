package work.lithos
package mutations


import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoAddressEncoder.NetworkPrefix
import org.ergoplatform.appkit._
import scorex.crypto.hash.Blake2b256
import sigma.VersionContext
import sigma.ast.SCollection.SByteArray
import sigma.ast.SigmaPredef.{PredefFuncInfo, PredefinedFunc, PredefinedFuncRegistry}
import sigma.ast.{ErgoTree, Global, Lambda, MethodCall, OperationInfo, SBoolean, SByte, SCollection, SGlobalMethods, SInt, SSigmaProp, SType, SUnsignedBigInt, SigmaBuilder, TransformingSigmaBuilder, Value}
import sigma.ast.syntax.{FalseSigmaProp, SValue, TrueSigmaProp}
import sigma.compiler.ir.CompiletimeIRContext
import sigma.compiler.phases.{SigmaBinder, SigmaTyper}
import sigma.compiler.{CompilerResult, CompilerSettings, SigmaCompiler}
import sigma.data.SigmaBoolean
import sigma.serialization.CoreByteWriter.ArgInfo
import sigmastate.interpreter.Interpreter.ScriptEnv

import scala.collection.JavaConverters
import scala.util.{Failure, Success, Try}


case class Contract(ergoTree: ErgoTree, mutators: Seq[Mutator] = Seq.empty[Mutator]) {
  def ergoContract(ctx: BlockchainContext): ErgoContract = ctx.newContract(ergoTree)

  def propBytes: Array[Byte] = ergoTree.bytes

  def hashedPropBytes: Array[Byte] = Blake2b256.hash(propBytes)
  def ergoTreeHex: String = Hex.toHexString(propBytes)

  def mainnetAddress: Address = Address.fromErgoTree(ergoTree, NetworkType.MAINNET)
  def testnetAddress: Address = Address.fromErgoTree(ergoTree, NetworkType.TESTNET)

  def sigmaBoolean: Option[SigmaBoolean] = ergoTree.toSigmaBooleanOpt
  def address(networkType: NetworkType): Address = Address.fromErgoTree(ergoTree, networkType)
  def address(ctx: BlockchainContext): Address = address(ctx.getNetworkType)
  override def toString: String = Hex.toHexString(hashedPropBytes)

  override def equals(obj: Any): Boolean = {
    obj match {
      case c: Contract => c.ergoTree.equals(this.ergoTree)
      case _ => false
    }
  }
}

object Contract {
  final val SIGMA_TRUE: Contract = new Contract(ErgoTree.fromProposition(TrueSigmaProp))
  final val SIGMA_FALSE: Contract = new Contract(ErgoTree.fromProposition(FalseSigmaProp))

  def apply(ergoTree: ErgoTree, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    new Contract(ergoTree, mutators)
  }

  def fromAddressString(address: String, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract(Address.create(address).toErgoContract.getErgoTree, mutators)
  }

  def fromErgoContract(contract: ErgoContract, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract(contract.getErgoTree, mutators)
  }

  def fromAddress(address: Address, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract(address.toErgoContract.getErgoTree, mutators)
  }

  def fromErgoScript(ctx: BlockchainContext, constants: Constants, script: String, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract(compileContract(script, constants, ctx.getNetworkType).get, mutators)
  }

  def compileContract(script: String, constants: Constants, networkType: NetworkType) = {
    val compiler = new SigmaCompiler(networkType.networkPrefix)
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion){
      val ergoTreeHeader = ErgoTree.defaultHeaderWithVersion(VersionContext.V6SoftForkVersion)
      Try(compiler.compile(JavaConverters.mapAsScalaMap(constants).toMap, script)(new CompiletimeIRContext)).flatMap {
        case CompilerResult(_, _, _, script: Value[SSigmaProp.type @unchecked]) if script.tpe == SSigmaProp =>
          Success(ErgoTree.fromProposition(ergoTreeHeader, script))
        case CompilerResult(_, _, _, script: Value[SBoolean.type @unchecked]) if script.tpe == SBoolean =>
          Success(ErgoTree.fromProposition(ergoTreeHeader, script.toSigmaProp))
        case other =>
          Failure(new Exception(s"Source compilation result is of type ${other.buildTree.tpe}, but `SBoolean` expected"))
      }
    }
  }
}



