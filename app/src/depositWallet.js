import { concat, encodeAbiParameters, getAddress, keccak256, toHex } from "viem";

// Port of polymarket-client (Python) polymarket/_internal/wallet.py —
// production WalletDerivationConfig values from polymarket/environments.py.
const FACTORY = "0x00000000000Fb5C9ADea0298D729A0CB3823Cc07";
const IMPLEMENTATION = "0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB";
const BEACON = "0x7A18EDfe055488A3128f01F563e5B479D92ffc3a";

const UUPS_CONST1 = "0xcc3735a920a3ca505d382bbc545af43d6000803e6038573d6000fd5b3d6000f3";
const UUPS_CONST2 = "0x5155f3363d3d373d3d363d7f360894a13ba1a3210667c828492db98dca3e2076";
const UUPS_PREFIX_BASE = 0x61003d3d8160233d3973n;
const BEACON_CONST1 = "0xb3582b35133d50545afa5036515af43d6000803e604d573d6000fd5b3d6000f3";
const BEACON_CONST2 = "0x1b60e01b36527fa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6c";
const BEACON_CONST3 = "0x60195155f3363d3d373d3d363d602036600436635c60da";
const BEACON_PREFIX_BASE = 0x6100523d8160233d3973n;

function depositWalletArgs(signer) {
  const walletId = `0x${signer.slice(2).toLowerCase().padStart(64, "0")}`;
  return encodeAbiParameters(
    [{ type: "address" }, { type: "bytes32" }],
    [FACTORY, walletId],
  );
}

function create2(bytecodeHash, args) {
  const salt = keccak256(args);
  const digest = keccak256(concat(["0xff", FACTORY, salt, bytecodeHash]));
  return getAddress(`0x${digest.slice(-40)}`);
}

function prefixBytes(base, argsHex) {
  const argsByteLength = BigInt((argsHex.length - 2) / 2);
  return toHex(base + (argsByteLength << 56n), { size: 10 });
}

export function deriveUupsDepositWallet(signer) {
  const args = depositWalletArgs(signer);
  const bytecode = concat([
    prefixBytes(UUPS_PREFIX_BASE, args),
    IMPLEMENTATION,
    "0x6009",
    UUPS_CONST2,
    UUPS_CONST1,
    args,
  ]);
  return create2(keccak256(bytecode), args);
}

export function deriveBeaconDepositWallet(signer) {
  const args = depositWalletArgs(signer);
  const bytecode = concat([
    prefixBytes(BEACON_PREFIX_BASE, args),
    BEACON,
    BEACON_CONST3,
    BEACON_CONST2,
    BEACON_CONST1,
    args,
  ]);
  return create2(keccak256(bytecode), args);
}
