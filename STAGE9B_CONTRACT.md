# Stage 9B agreed integration contract (root decision)

Retain existing package/DTO class names to avoid unrelated renaming, but bump canonical
snapshot numeric schema to 2, Drive manifest to 3, sync metadata to 2, outbox to 3 and
bundle to 2. Retire all older readers explicitly, preserve unsupported input bytes.
Snapshot page/pin topology stays: do not create new independent photos/pages authorities.

Annotation model: one common immutable note content/style/transform representation for
PDF and photos; stable IDs on notes, paths and measurements; immutable committed scalar
values and detached transient drafts. Existing names may be type aliases of the same real
shared model for surface readability, not parallel formats or compatibility fallbacks.
Use normalized visible-surface coordinates, center anchors for notes/shapes; ratio font
size to surface height and stroke to maximum dimension. Shapes use widthRatio/heightRatio
only. Remove redundant absolute fields and 800px fallback. PDF measurements/calibration
compute distances using normalized points mapped to actual PDF page source dimensions
(points), not bitmap sample size. PageScale becomes pointsPerFoot (schema2).
Keep source geometry/fingerprint consistent with crop/rotation-resolved visible rectangle.
Do NOT compare device-local snapshot revisions with another device's remote cursor or
change established conflict policy. Local expected-before identity/revision fences edits.
Clear-page is an undoable reducer transaction, not global history destruction. Only actual
authoritative replacement follows existing epoch/history invalidation semantics.

Asset interface will replace whole-photo maps with immutable PhotoAssetSet and reopenable
PhotoAsset sources (descriptor + open stream), using the existing PhotoDescriptor fields
and validators where possible. Property name photoFiles may remain temporarily during
integration but its final production type cannot be Map<String,ByteArray>. Every final
production source is file-backed; tests may construct explicit synthetic byte sources.
Snapshot/pin photo references continue safe internal occurrence filenames. Asset hash is
byte identity, not document/occurrence identity. Asset set lifetime/retention must preserve
frozen queued/outbox bytes; no raw-path TOCTOU bypass of the anchored photo store.
Root will publish exact methods before asset implementation callers are migrated.

Root owns shared docs and build execution, reviewers read-only. MainActivity has one
exclusive writer for annotation integration; all other lanes supply precise proposed edits
for later root/owner integration, not concurrent MainActivity writes. Shared PayloadSecurity
has one owner after annotation schema is settled; no concurrent edits.

## Exact asset interface contract (root, Stage9B)

New production package stage9b/PhotoAssets.kt is owned by asset-core worker.
- interface PhotoAsset { val descriptor: stage5.PhotoDescriptor; fun open(): InputStream }
  Sources reopen immutable managed files; caller owns each stream. open must not expose
  untrusted raw paths. Descriptor fields reuse byteCount, sha256 and imageInfo.
- class PhotoAssetSet private constructor(...) : Map<String, PhotoAsset> by immutable map
  companion: EMPTY; of(Map<String,PhotoAsset>): PhotoAssetSet. Copy only descriptors and
  stable immutable source handles, never bytes. No whole-map bytes conversion in production.
  val descriptors: Map<String,PhotoDescriptor>; val totalBytes: Long.
  The set is a reusable immutable handle, not an ownership-destructive closeable. File-store
  reachability and bounded persistent retention own disk cleanup; opening creates no long-
  lived handles. A source remains usable through any queued/outbox/retry lifecycle.
- fun validatePhotoAssets(snapshot: DocumentSnapshotV1, assets: PhotoAssetSet,
  expectedDescriptors: Map<String,PhotoDescriptor>? = null): PhotoAssetSet
  requires exact occurrence-reference keys, per-file/aggregate counts/dims/hashes and verifies
  each file via bounded stream. Decode validation may materialize ONE capped 25MiB file to
  reuse the current sampled decoder, never an entire set; transfer memory budget must expose
  and measure this rather than pretending the cap is a 64KiB total. No repeated deep copies.
- fun copyPhotoAsset(asset: PhotoAsset, output: OutputStream): Long streams bounded
  <=64KiB buffer, validates byte count and SHA-256 including actual EOF, closes input only.
- fun photoAssetsFromDescriptors(descriptors: Map<String,PhotoDescriptor>,
  open: (String)->InputStream): PhotoAssetSet builds validated immutable handles.
  Opener implementations MUST prove anchored containment/stable file identity/immutability.
  This utility does not bless an arbitrary File path.
- fun photoAssetBytesForValidation(asset: PhotoAsset): ByteArray is internal decoder-only
  per-file bounded support, not a transport or capture API.

Properties named photoFiles in UploadRequest/RemoteSnapshotEnvelope/Bundle/Coordinator/
DurablePendingUpload change type to PhotoAssetSet, default PhotoAssetSet.EMPTY where empty
is legitimate. toDurable/rebase share immutable handles and DO NOT mapValues copyOf.
The in-memory fake is a deterministic remote fixture and must freeze handles using its own
managed backing factory or explicit test source, never alias mutable inputs.

Captured current document assets are frozen/deduplicated into managed content-hash files
before capture returns (under existing barrier and anchored photo operations); persistent
immutable pool owns retention, with bounded disk/count admission and conservative GC.
Outbox streams these into its existing immutable content directories and reopens descriptors
without accumulating arrays; old current-format outbox recovery hardening stays intact.
A reopened outbox source must not be deleted while handed to pending/coordinator work.
Downloading and bundle parsing require an explicit app-private staging directory/sink
constructor argument; no process-global temp assumptions on Android. Staging sets retain
owned files through import/acceptance/retry; cleanup via complete ownership/retention rules.
New immutable pool limit/cleanup must not become unbounded permanent abandoned task files.

PhotoAssetStore/PhotoContentTransaction own streaming local capture/staging. MainActivity
owner will integrate their exact capture API from a report after its annotation pass.
DriveGateway+new remote transport owns asset separate transfer/manifest3, strict manifests
in a NEW RemoteManifestCodec.kt, so root can independently update PayloadSecurity snapshot
validator. Coordinator+Metadata/Outbox owns types/lifetimes/outbox3/metadata2. Bundle owns
bundle2 and explicit local staging. Root owns all shared existing tests and PayloadSecurity.
No worker edits another lane's production files. Suggest integration changes in task reports.

Durable resumption belongs to remote transport, injected task/account-scoped app-private
state directory; persist pre-generated file IDs before first create. Use official server
range acknowledgement and checksum when available, not custom hash property as proof.
No remote GC unless all-device/recovery reachability is proven.

## Root integration checkpoint

The root has run a frozen-source production compilation. Its diagnostic errors are
in the task evidence directory as integration-compile-1-errors.txt (not final gates).
Leaf workers should resolve errors in their own files, report remaining cross-owner
call-site requirements, and return their scoped handoff. Do not wait for the entire
application or another worker to finish, broaden file ownership, or perform another
whole-repository audit. Root owns the final unified compilation, integration and review.
The current local repository now calls the shared Stage5 snapshot validator instead
of maintaining another obsolete geometry validator; snapshot2 DTOs must use the
ratio-only fields and stable IDs already defined above.

The root owns the new CurrentMetadataWireTest, CanonicalAnnotationValidationTest,
TestPhotoAssets JVM helper and Android TransferMemoryInstrumentedTest. Existing
worker assignments for new helper files do not permit overwriting these oracles.
The measured Android transfer/outbox budget gate uses four valid encoded photo
files at the 25 MiB per-file boundary: <=128 MiB incremental Java heap, <=64 MiB
incremental native decoder heap, <=64 KiB asset read buffers. This is an explicit
qualification budget, not a claim of a passing measurement before execution.

## Pending retry provenance correction

An ordinary interrupted upload is not permission to replace newer live edits.
PendingUpload/DurablePendingUpload carry PendingUploadIntent with values
AUTOMATIC_RETRY and EXPLICIT_CONFLICT_REPLAY. New capture defaults to automatic.
Persist pendingUploadIntent in the scoped metadata and include it in validation,
freezing, rebasing, equality and recovery identity. A pending wire record missing
or containing an unknown intent is rejected and preserved; no legacy inference.
The outbox remains the immutable byte authority, not a second intent authority.

Only successful explicit conflict acceptance promotes the selected preserved local
snapshot to EXPLICIT_CONFLICT_REPLAY, durably before replay scheduling. Preserve
that intent across process recreation and clear it with the acknowledged pending.
Automatic retry captures current canonical live state through the established
transaction/session boundaries. Equal snapshots may reuse immutable pending assets
without applying canonical state. Different live state must durably supersede the
ordinary pending before network publication; failed replacement preserves the old
sidecar/lease and never rolls live state back. Explicit replay uses the selected
frozen snapshot, never the remote state just applied by conflict acceptance.

Keep existing cursor/conflict/ambiguous-completion handling, source/account/root
fences and remote-then-local conflict behavior. Finalization must release only the
accepted/superseded owned claims after their durable handoff, not newer work.
Required regressions include same-document/token auth A-B-A, ordinary retry after
new edits, unchanged byte reuse, failed supersession retention, and explicit
acceptance followed by coordinator recreation before replay.
