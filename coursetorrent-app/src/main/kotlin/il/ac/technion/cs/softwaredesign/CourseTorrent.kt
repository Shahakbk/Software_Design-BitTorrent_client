@file:Suppress("UNCHECKED_CAST")

package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import java.security.MessageDigest
import il.ac.technion.cs.softwaredesign.exceptions.PeerChokedException
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.PieceHashException
import java.lang.Exception
import java.lang.Thread.sleep
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.*
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

val URL_ERROR = -1
/**
 * This is the class implementing CourseTorrent, a BitTorrent client.
 *
 * Currently specified:
 * + Parsing torrent metainfo files (".torrent" files)
 * + Communication with trackers (announce, scrape).
 * + Communication with peers (downloading! uploading!)
 */
class CourseTorrent @Inject constructor(val announcesStorage: Announces,
                                        val peersStorage: Peers,
                                        val trackerStatisticsStorage: TrackerStatistics,
                                        val torrentStatisticsStorage: TorrentStatistics,
                                        val torrentFilesStorage: TorrentFiles,
                                        val piecesStorage: Pieces,
                                        val httpClient: HttpClient) {
    companion object {
        private val md = MessageDigest.getInstance("SHA-1")
        private val studentId = byteArray2Hex(md.digest((204596597 + 311242440).toString().toByteArray())).take(6)
        private val randomGenerated = (1..6).map{(('A'..'Z')+('a'..'z')+('0'..'9')).random()}.joinToString("")
    }

    //TODO check for a faster way to validate infohash instead of announcesStorage.read(infohash)


    private val peerId = "-CS1000-$studentId$randomGenerated"
    private val port = "6882" // TODO change? randomize?
    private var serverSocket: ServerSocket? = null
    private val activeSockets: HashMap<String, HashMap<KnownPeer, Socket?>> = hashMapOf() //TODO KnownPeer is a key, and therefore his values have no significance
    private val activePeers: HashMap<String, HashMap<KnownPeer, ConnectedPeer>> = hashMapOf() //TODO ConnectedPeer is the one who holds the actual values
    //TODO peerRequests and peersBitMap in handleSmallMessages
    private val peersBitMap: HashMap<String, HashMap<KnownPeer, HashMap<Long, Byte>>> = hashMapOf()
    private val peersRequests: HashMap<String, HashMap<KnownPeer, HashMap<Long, ArrayList<Long>>>> = hashMapOf() // Maps infohash->KnownPeer->PieceIndex->PartsList
    private var keepAliveTimer = LocalDateTime.now()
    /**
     * Load in the torrent metainfo file from [torrent]. The specification for these files can be found here:
     * [Metainfo File Structure](https://wiki.theory.org/index.php/BitTorrentSpecification#Metainfo_File_Structure).
     *
     * After loading a torrent, it will be available in the system, and queries on it will succeed.
     *
     * This is a *create* command.
     *
     * @throws IllegalArgumentException If [torrent] is not a valid metainfo file.
     * @throws IllegalStateException If the infohash of [torrent] is already loaded.
     * @return The infohash of the torrent, i.e., the SHA-1 of the `info` key of [torrent].
     */
    //TODO need to check if allready started, add timeStamps to the torrent.
    fun load(torrent: ByteArray): CompletableFuture<String> {
        return CompletableFuture.supplyAsync {
            parseTorrent(torrent)
        }.thenCompose { (infohash, metaInfo) ->
            announcesStorage.read(infohash).thenCompose { value ->
                if (null != value) {
                    throw IllegalStateException("load: infohash was already loaded")
                }
                val announcements = (metaInfo["announce-list"] ?: metaInfo["announce"])
                announcesStorage.write(infohash, Bencoder.encodeStr(announcements).toByteArray())
            }.thenCompose {
                val infoRawByes = metaInfo["info"] as ByteArray
                val infoDict = Bencoder(infoRawByes).decodeTorrent() as HashMap<*,*>
                var numPieces: Long = 0
                val piecesMap = hashMapOf<Long, Piece>()
                val pieceLength = infoDict["piece length"] as Long
                val torrentFiles = arrayListOf<TorrentFile>() // TODO maybe hashmap?

                if (infoDict.containsKey("files")) {
                    // Multiple files
                    val files = infoDict["files"] as List<HashMap<String, Any>>
                    var totalLength: Long = 0

                    files.forEachIndexed { i, file ->
                        val fileName = (file["path"] as List<String>).joinToString("\\")
                        val fileLength = (file["length"] as Long)
                        // At this stage, totalLength is the current offset before adding fileLength
                        val torrentFile = TorrentFile(fileName, i.toLong(), totalLength, fileLength)
                        totalLength += fileLength
                        torrentFiles.add(torrentFile)
                    }
                    numPieces = ceil(totalLength/(infoDict["piece length"] as Long).toDouble()).toLong()
                    for (i in 0 until numPieces) {
                        piecesMap[i] = Piece(i, pieceLength, (infoDict["pieces"] as ByteArray).drop(20 * i.toInt()).take(20).toByteArray(), null)
                    }
                } else {
                    // Single file
                    numPieces = ceil((infoDict["length"] as Long)/(infoDict["piece length"] as Long).toDouble()).toLong()
                    for (i in 0 until numPieces) {
                        piecesMap[i] = Piece(i, pieceLength, (infoDict["pieces"] as ByteArray).drop(20 * i.toInt()).take(20).toByteArray(), null)
                    }
                    torrentFiles.add(TorrentFile(infoDict["name"] as String, 0, 0, infoDict["length"] as Long))
                }
                piecesStorage.write(infohash, Bencoder.encodeStr(piecesMap).toByteArray()).thenCompose {
                    torrentFilesStorage.write(infohash, Bencoder.encodeStr(torrentFiles).toByteArray()) }.thenApply {
                    infohash }
            }
        }
    }

    /**
     * Remove the torrent identified by [infohash] from the system.
     *
     * This is a *delete* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    // TODO make sure all storages are cleared
    fun unload(infohash: String): CompletableFuture<Unit> {
        return announces(infohash).thenCompose { announces  ->
            var future = CompletableFuture.completedFuture(Unit)
            announces as List<List<String>>
            announces.forEach { trackerTier: List<String> ->
                trackerTier.forEach { tracker ->
                    future = future.thenCompose {
                        trackerStatisticsStorage.delete(infohash + "_" + tracker) }
                }
            }
            future
        }.exceptionally {
            throw IllegalArgumentException("unload: infohash isn't loaded")
        }.thenCompose {
            peersStorage.delete(infohash)
        }.thenCompose {
            piecesStorage.delete(infohash)
        }.thenCompose {
           torrentFilesStorage.delete(infohash)
        }.thenCompose {
            announcesStorage.delete(infohash)
        }
    }

    /**
     * Return the announce URLs for the loaded torrent identified by [infohash].
     *
     * See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more information. This method behaves as follows:
     * * If the "announce-list" key exists, it will be used as the source for announce URLs.
     * * If "announce-list" does not exist, "announce" will be used, and the URL it contains will be in tier 1.
     * * The announce URLs should *not* be shuffled.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Tier lists of announce URLs.
     */
    fun announces(infohash: String): CompletableFuture<List<List<String>>> {
        return announcesStorage.read(infohash).thenApply { announcesRaw ->
            if (null == announcesRaw) throw IllegalArgumentException("announces: infohash wasn't loaded")
            val bencoder = Bencoder(announcesRaw)
            bencoder.decodeData()
        }.thenCompose { announces ->
            if (announces is String) {
                CompletableFuture.completedFuture(listOf(listOf(announces)))
            } else {
                CompletableFuture.completedFuture(announces as List<List<String>>)
            }
        }
    }

    /**
     * Send an "announce" HTTP request to a single tracker of the torrent identified by [infohash], and update the
     * internal state according to the response. The specification for these requests can be found here:
     * [Tracker Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_HTTP.2FHTTPS_Protocol).
     *
     * If [event] is [TorrentEvent.STARTED], shuffle the announce-list before selecting a tracker (future calls to
     * [announces] should return the shuffled list). See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more
     * information on shuffling and selecting a tracker.
     *
     * [event], [uploaded], [downloaded], and [left] should be included in the tracker request.
     *
     * The "compact" parameter in the request should be set to "1", and the implementation should support both compact
     * and non-compact peer lists.
     *
     * Peer ID should be set to "-CS1000-{Student ID}{Random numbers}", where {Student ID} is the first 6 characters
     * from the hex-encoded SHA-1 hash of the student's ID numbers (i.e., `hex(sha1(student1id + student2id))`), and
     * {Random numbers} are 6 random characters in the range [0-9a-zA-Z] generated at instance creation.
     *
     * If the connection to the tracker failed or the tracker returned a failure reason, the next tracker in the list
     * will be contacted and the announce-list will be updated as per
     * [BEP 12](http://bittorrent.org/beps/bep_0012.html).
     * If the final tracker in the announce-list has failed, then a [TrackerException] will be thrown.
     *
     * This is an *update* command.
     *
     * @throws TrackerException If the tracker returned a "failure reason". The failure reason will be the exception
     * message.
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return The interval in seconds that the client should wait before announcing again.
     */
    fun announce(infohash: String, event: TorrentEvent, uploaded: Long, downloaded: Long, left: Long): CompletableFuture<Int> {
        return announces(infohash).thenCompose { announces ->
            if (event == TorrentEvent.STARTED) {
                announcesStorage.shuffleTrackers(infohash, announces).thenApply { announces }
            } else { //TODO: added else so shuffletracker will allways happen because thenApply is not enough if no 1 uses this certain announces(doesnt ^thenCompose if remove else) (Abir)
                CompletableFuture.completedFuture(announces)
            }
        }.thenCompose { announces ->
            announceAux(infohash,event,announces,0,0,uploaded,downloaded,left)
        }
    }

    private fun announceAux(infohash: String, event: TorrentEvent, announces: List<List<String>>, tier:Int, trackerIdx:Int,
                            uploaded: Long, downloaded: Long, left: Long ): CompletableFuture<Int> {
        if (tier >= announces.size)
            throw TrackerException("announce: last tracker failed")
        if (trackerIdx >= announces[tier].size)
            return announceAux(infohash, event, announces, tier + 1, 0, uploaded, downloaded, left)
        val tracker = announces[tier][trackerIdx]
        val future = CompletableFuture.completedFuture(0).thenCompose {
            val url = Announces.createAnnounceURL(infohash, tracker, peerId, port, uploaded, downloaded, left, event)
            httpClient.setURL(url)
                val response = httpClient.getResponse()
                val responseDict = (Bencoder(response).decodeResponse()) as HashMap<*, *>
                if (responseDict.containsKey("failure reason")) {
                    val reason = responseDict["failure reason"] as String
                    trackerStatisticsStorage.addFailure(infohash, tracker, reason).thenApply { Pair(false, 0) }
                } else {
                    peersStorage.addPeers(infohash, responseDict["peers"] as List<Map<*, *>>?).thenCompose {
                        announcesStorage.moveTrackerToHead(infohash, announces, announces[tier], tracker)
                    }.thenCompose {
                        trackerStatisticsStorage.addScrape(responseDict, infohash, tracker)
                    }.thenCompose {
                        CompletableFuture.completedFuture(Pair(true, responseDict.getOrDefault("interval", 0) as Int))
                    }
                }
        }.exceptionally { exc ->
            if(exc.cause is java.lang.IllegalArgumentException){
                throw exc
            }
            Pair(false, URL_ERROR) }
        return future.thenCompose { (isDone: Boolean, interval: Int) ->
            if (isDone) {
                CompletableFuture.completedFuture(interval)
            } else {
                if (interval == URL_ERROR) {
                    trackerStatisticsStorage.addFailure(infohash, tracker, "announce: URL connection failed").thenCompose {
                        announceAux(infohash, event, announces, tier, trackerIdx + 1, uploaded, downloaded, left)
                    }
                } else {
                    announceAux(infohash, event, announces, tier, trackerIdx + 1, uploaded, downloaded, left)
                }
            }
        }
    }

    /**
     * Scrape all trackers identified by a torrent, and store the statistics provided. The specification for the scrape
     * request can be found here:
     * [Scrape Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_.27scrape.27_Convention).
     *
     * All known trackers for the torrent will be scraped.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun scrape(infohash: String): CompletableFuture<Unit> {
        return announces(infohash).thenCompose { announces ->
            var future = CompletableFuture.completedFuture(Unit)
            for (tracker: String in announces.flatten()) {
                future = future.thenCompose {
                    val supportsScraping = tracker.substring(tracker.lastIndexOf('/')).startsWith("/announce")
                    if (supportsScraping) {
                        val url = TrackerStatistics.createScrapeURL(infohash, tracker)
                        httpClient.setURL(url)
                        val response = (Bencoder(httpClient.getResponse()).decodeResponse()) as HashMap<*, *>
                        val responseDict = (response["files"] as HashMap<*, *>).values.first() as HashMap<*, *>
                        trackerStatisticsStorage.addScrape(responseDict, infohash, tracker)
                    } else {
                        // Tracker doesn't support scraping
                        trackerStatisticsStorage.addFailure(infohash, tracker, "scrape: URL connection failed")
                    }
                }.exceptionally {
                    trackerStatisticsStorage.addFailure(infohash, tracker, "scrape: URL connection failed")
                }
            }
            future
        }
    }

    /**
     * Invalidate a previously known peer for this torrent.
     *
     * If [peer] is not a known  peer for this torrent, do nothing.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun invalidatePeer(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenApply { value ->
            if (null == value) throw IllegalArgumentException("invalidePeer: infohash wasn't loaded")
        }.thenCompose {
            peersStorage.read(infohash)
        }.thenCompose { peersList ->
            if (null == peersList) {
                CompletableFuture.completedFuture(Unit)
            } else {
                val updatedPeersList = (Bencoder(peersList).decodeData()) as ArrayList<KnownPeer>
                if (updatedPeersList.contains(peer)) {
                    updatedPeersList.remove(peer)
                    peersStorage.write(infohash, Bencoder.encodeStr(updatedPeersList).toByteArray())
                } else {
                    CompletableFuture.completedFuture(Unit)
                }
            }
        }
    }

    /**
     * Return all known peers for the torrent identified by [infohash], in sorted order. This list should contain all
     * the peers that the client can attempt to connect to, in ascending numerical order. Note that this is not the
     * lexicographical ordering of the string representation of the IP addresses: i.e., "127.0.0.2" should come before
     * "127.0.0.100".
     *
     * The list contains unique peers, and does not include peers that have been invalidated.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Sorted list of known peers.
     */
    fun knownPeers(infohash: String): CompletableFuture<List<KnownPeer>> {
        return announcesStorage.read(infohash).thenCompose { value ->
            if (null == value) throw IllegalArgumentException("announces: infohash wasn't loaded")
            peersStorage.read(infohash)
        }.thenCompose { peerList ->
            if (null == peerList) {
                CompletableFuture.completedFuture(listOf())
            } else {
                val knownPeersList = Bencoder(peerList).decodeData() as ArrayList<KnownPeer>
                knownPeersList.sortBy{ ipToInt(it.ip) }
                CompletableFuture.completedFuture(knownPeersList.toList())
            }
        }
    }

    /**
     * Return all known statistics from trackers of the torrent identified by [infohash]. The statistics displayed
     * represent the latest information seen from a tracker.
     *
     * The statistics are updated by [announce] and [scrape] calls. If a response from a tracker was never seen, it
     * will not be included in the result. If one of the values of [ScrapeData] was not included in any tracker response
     * (e.g., "downloaded"), it would be set to 0 (but if there was a previous result that did include that value, the
     * previous result would be shown).
     *
     * If the last response from the tracker was a failure, the failure reason would be returned ([ScrapeData] is
     * defined to allow for this). If the failure was a failed connection to the tracker, the reason should be set to
     * "Connection failed".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return A mapping from tracker announce URL to statistics.
     */
    fun trackerStats(infohash: String): CompletableFuture<Map<String, ScrapeData>> {
        return announces(infohash).thenCompose { trackers ->
            var future = CompletableFuture.completedFuture(hashMapOf<String, ScrapeData>())
            for (tracker in trackers.flatten()) {
                future = future.thenCompose { statsMap ->
                    trackerStatisticsStorage.read(infohash + "_" + tracker).thenApply { scrapeData ->
                        if (null != scrapeData)
                            statsMap[tracker] = Bencoder(scrapeData).decodeData() as ScrapeData
                        statsMap
                    }
                }
            }
            future.thenApply { map -> map.toMap() }
        }
    }

    /**
     * Return information about the torrent identified by [infohash]. These statistics represent the current state
     * of the client at the time of querying.
     *
     * See [TorrentStats] for more information about the required data.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Torrent statistics.
     */
    //TODO do it later
    fun torrentStats(infohash: String): CompletableFuture<TorrentStats> {
        return torrentStatisticsStorage.read(infohash).thenApply { torrentStatsRaw ->
            if (null == torrentStatsRaw) throw IllegalArgumentException("torrentStats: infohash wasn't loaded")
            val bencoder = Bencoder(torrentStatsRaw)
            bencoder.decodeData() as TorrentStats
        }
    }

    /**
     * Start listening for peer connections on a chosen port.
     *
     * The port chosen should be in the range 6881-6889, inclusive. Assume all ports in that range are free.
     *
     * For a given instance of [CourseTorrent], the port sent to the tracker in [announce] and the port chosen here
     * should be the same.
     *
     * This is a *update* command. (maybe)
     *
     * @throws IllegalStateException If already listening.
     */
    fun start(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            if (null != serverSocket) {
                throw java.lang.IllegalStateException("start: client is already listening on the specified port")
            }
            serverSocket = ServerSocket(port.toInt()) // Socket server to allow peers to connect to courseTorrent
            serverSocket!!.soTimeout = 100 // TODO set timeout so as to not get stuck while accept()ings
            //TODO do we want a storage or local variable to hold all the torrents that need to get timeStamps

        }
    }

    /**
     * Disconnect from all connected peers, and stop listening for new peer connections
     *
     * You may assume that this method is called before the instance is destroyed, and perform clean-up here.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalStateException If not listening.
     */
    fun stop(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            activeSockets.mapValues { torrentSockets ->
                torrentSockets.value.mapValues { socketMap ->
                    if (socketMap.value?.isClosed == false) // TODO make sure isClosed is not called on null
                        socketMap.value?.close()
                }
            }
            serverSocket?.close()
        }
    }

    /**
     * Connect to [peer] using the peer protocol described in [BEP 003](http://bittorrent.org/beps/bep_0003.html).
     * Only connections over TCP are supported. If connecting to the peer failed, an exception is thrown.
     *
     * After connecting, send a handshake message, and receive and process the peer's handshake message. The peer's
     * handshake will contain a "peer_id", and future calls to [knownPeers] should return this peer_id for this peer.
     *
     * If this torrent has anything downloaded, send a bitfield message.
     *
     * Wait 100ms, and in that time handle any bitfield or have messages that are received.
     *
     * In the handshake, the "reserved" field should be set to 0 and the peer_id should be the same as the one that was
     * sent to the tracker.
     *
     * [peer] is equal to (and has the same [hashCode]) an object that was returned by [knownPeers] for [infohash].
     *
     * After a successful connection, this peer should be listed by [connectedPeers]. Peer connections start as choked
     * and not interested for both this client and the peer.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not known.
     * @throws PeerConnectException if the connection to [peer] failed (timeout, connection closed after handshake, etc.)
     */
    fun connect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        lateinit var socket: Socket
        return knownPeers(infohash).thenCompose { peersList ->
            if (!peersList.contains(peer)) {
                throw IllegalArgumentException("connect: peer is not known")
            }
            announcesStorage.read(infohash)
        }.thenCompose { value ->
            if (null == value) {
                throw IllegalArgumentException("connect: infohash is not loaded")
            }
            // Open a socket and send a handshake message
            socket = Socket(peer.ip, peer.port)
            val socketOutputStream = socket.getOutputStream()
            //TODO: infohash to byte array or infohash hex2bytearray and peerId also ?
            socketOutputStream.write(WireProtocolEncoder.handshake(Bencoder.decodeHexString(infohash)!!, peerId.toByteArray()))
            // Verify handshake response
            val response = socket.getInputStream().readNBytes(68)
            if (WireProtocolDecoder.handshake(response).infohash.contentEquals(Bencoder.decodeHexString(infohash)!!)) {
                //TODO: how to get bitmap out of message \ create message with bit map
                val mapSockets = activeSockets[infohash] ?: hashMapOf()
                mapSockets[peer] = socket
                activeSockets[infohash] = mapSockets
                val mapPeers = activePeers[infohash] ?: hashMapOf()
                mapPeers[peer] = ConnectedPeer(peer)
                activePeers[infohash] = mapPeers
                // Init bitfield
                piecesStorage.read(infohash).thenApply { pieceMapBytes ->
                    val pieceMap = Bencoder(pieceMapBytes as ByteArray).decodeData() as HashMap<Long, Piece>
                    val bitField = ByteArray(pieceMap.size)
                    pieceMap.forEach{ index, piece ->
                        bitField[index.toInt()] = if (null == piece.data) 0.toByte() else 1.toByte()
                    }
                    if(bitField.contains(1.toByte())){
                        socketOutputStream.write(WireProtocolEncoder.encode(5.toByte(),bitField,0))
                    }
                    //TODO maybe we dont want sleep or handleSmallMessages
                }.thenCompose {
                    sleep(100)
                    CompletableFuture.completedFuture(Unit)
                    //TODO put handleSmallMessages Back instead of the future above
                    /*handleSmallMessages()*/ } //handling bitfields and have request after 100ms
            } else {
                val map = activeSockets[infohash] ?: hashMapOf()
                map[peer] = null
                activeSockets[infohash] = map
                socket.close()
                CompletableFuture.completedFuture(Unit)
            }
        }.exceptionally { exc ->
            if(exc.cause is java.lang.IllegalArgumentException){
                throw exc
            }
            // TODO is this necessery? since socket will never be closed here? maybe we can just close (what does connection closed after handshake means) (Abir)
            if (!socket.isClosed) {
                socket.close()
            }
            throw PeerConnectException("connect: connection to peer failed")
        }
    }

    /**
     * Disconnect from [peer] by closing the connection.
     *ז
     * There is no need to send any messages.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun disconnect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenApply { announce ->
            if (null == announce) {
                throw IllegalArgumentException("disconnect: infohash is not loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("disconnect: peer is not connected")
            }
            val mapSockets = activeSockets[infohash]
            if (null != mapSockets) {
                mapSockets[peer] = null
                activeSockets[infohash] = mapSockets
            }
            val mapPeers = activePeers[infohash]
            if (null != mapPeers) {
                mapPeers.remove(peer)
                activePeers[infohash] = mapPeers
            }
        }
    }

    /**
     * Return a list of peers that this client is currently connected to, with some statistics.
     *
     * See [ConnectedPeer] for more information.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     */
    fun connectedPeers(infohash: String): CompletableFuture<List<ConnectedPeer>> {
        return announcesStorage.read(infohash).thenApply { value ->
            if(null == value) throw IllegalArgumentException("connectedPeers: infohash isn't loaded")
            activePeers[infohash]?.values?.toList() ?: listOf()
        }
    }

    /**
     * Send a choke message to [peer], which is currently connected. Future calls to [connectedPeers] should show that
     * this peer is choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun choke(infohash: String, peer: KnownPeer): CompletableFuture<Unit>{
        return announcesStorage.read(infohash).thenApply { value ->
            if (null == value) {
                throw IllegalArgumentException("choke: infohash isn't loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("choke: peer is not connected")
            }
            val socket = activeSockets[infohash]!![peer]
            socket!!.getOutputStream().write(WireProtocolEncoder.encode(0.toByte()))
            val connectedPeer = activePeers[infohash]!![peer]!!
            connectedPeer.amChoking = true
            activePeers[infohash]!![peer] = connectedPeer
        }
    }

    /**
     * Send an unchoke message to [peer], which is currently connected. Future calls to [connectedPeers] should show
     * that this peer is not choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun unchoke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenApply { value ->
            if (null == value) {
                throw IllegalArgumentException("unchoke: infohash isn't loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("unchoke: peer is not connected")
            }
            val socket = activeSockets[infohash]!![peer]
            socket!!.getOutputStream().write(WireProtocolEncoder.encode(1.toByte()))
            val connectedPeer = activePeers[infohash]!![peer]!!
            connectedPeer.amChoking = false
            activePeers[infohash]!![peer] = connectedPeer
        }
    }

    /**
     * Handle any messages that peers have sent, and send keep-alives if needed, as well as interested/not interested
     * messages.
     *
     * Messages to receive and handle from peers:
     *
     * 1. keep-alive: Do nothing.
     * 2. unchoke: Mark this peer as not choking in future calls to [connectedPeers].
     * 3. choke: Mark this peer as choking in future calls to [connectedPeers].
     * 4. have: Update the internal state of which pieces this client has, as seen in future calls to [availablePieces]
     * and [connectedPeers].
     * 5. request: Mark the peer as requesting a piece, as seen in future calls to [requestedPieces]. Ignore if the peer
     * is choked.
     * 6. handshake: When a new peer connects and performs a handshake, future calls to [knownPeers] and
     * [connectedPeers] should return it.
     *
     * Messages to send to each peer:
     *
     * 1. keep-alive: If it has been more than one minute since we sent a keep-alive message (it is OK to keep a global
     * count)
     * 2. interested: If the peer has a piece we don't, and we're currently not interested, send this message and mark
     * the client as interested in future calls to [connectedPeers].
     * 3. not interested: If the peer does not have any pieces we don't, and we're currently interested, send this
     * message and mark the client as not interested in future calls to [connectedPeers].
     *
     * These messages can also be handled by different parts of the code, as desired. In that case this method can do
     * less, or even nothing. It is guaranteed that this method will be called reasonably often.
     *
     * This is an *update* command. (maybe)
     */
    fun handleSmallMessages(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            val timeForKeepAlive = Duration.between(keepAliveTimer, LocalDateTime.now()).toMinutes()
            if (timeForKeepAlive >= 1) {
                keepAliveTimer = LocalDateTime.now()
                //TODO send keep-alive.
            }
            activeSockets.forEach { infoString ->
                val infohash = infoString.key
                infoString.value.forEach { socketMap ->
                    try {
                        val socket = socketMap.value
                        val peer = socketMap.key
                        if (socket != null) {
                            socket.soTimeout = 100
                            val msgLenBytes = (socket.getInputStream().readNBytes(4))
                            val msgLen = getMsgLength(msgLenBytes)
                            if (msgLen > 0) {
                                val restOfMsg = socket.getInputStream().readNBytes(msgLen)
                                val msg = msgLenBytes.plus(restOfMsg)
                                val msgId = WireProtocolDecoder.messageId(msg)
                                //choke
                                if (msgId == 0.toByte()) {
                                    handleChoke(infohash, peer)
                                    //unchoke
                                } else if (msgId == 1.toByte()) {
                                    handleUnChoke(infohash, peer)
                                    //have
                                } else if (msgId == 4.toByte()) {
                                    handleHave(infohash, peer, msg)
                                    //bitfield
                                } else if (msgId == 5.toByte()) {
                                    handleBitField(infohash, peer, msg)
                                    //request
                                } else if (msgId == 6.toByte()) {

                                }
                            }
                        }
                    }catch(e: Exception){
                        if(e.cause !is SocketTimeoutException){
                            activeSockets[infohash]!![socketMap.key] = null
                        }
                    }
                }
            }
        }.thenApply {
            serverSocket!!.soTimeout = 100
            while(true){
                val socket = serverSocket!!.accept()
                val ip = socket.inetAddress.hostAddress
                //TODO why is port not as expected
                val port = socket.port
                val handshake = WireProtocolDecoder.handshake(socket.getInputStream().readNBytes(68))
                val peer = KnownPeer(ip,port, String(handshake.peerId))
                val infohash = byteArray2Hex(handshake.infohash)
                val actSockets = activeSockets[infohash] ?: hashMapOf()
                actSockets[peer] = socket
                activeSockets[infohash] = actSockets
                val mapPeers = activePeers[infohash] ?: hashMapOf()
                mapPeers[peer] = ConnectedPeer(peer)
                activePeers[infohash] = mapPeers
            }
        }.exceptionally { /* accept didnt have anything to accept */  }
    }

    /**
     * Download piece number [pieceIndex] of the torrent identified by [infohash].
     *
     * Attempt to download a complete piece by sending a series of request messages and receiving piece messages in
     * response. This method finishes successfully (i.e., the [CompletableFuture] is completed) once an entire piece has
     * been received, or an error.
     *
     * Requests should be of piece subsets of length 16KB (2^14 bytes). If only a part of the piece is downloaded, an
     * exception is thrown. It is unspecified whether partial downloads are kept between two calls to requestPiece:
     * i.e., on failure, you can either keep the partially downloaded data or discard it.
     *
     * After a complete piece has been downloaded, its SHA-1 hash will be compared to the appropriate SHA-1 has from the
     * torrent meta-info file (see 'pieces' in the 'info' dictionary), and in case of a mis-match an exception is
     * thrown and the downloaded data is discarded.
     *
     * This is an *update* command.
     *
     * @throws PeerChokedException if the peer choked the client before a complete piece has been downloaded.
     * @throws PeerConnectException if the peer disconnected before a complete piece has been downloaded.
     * @throws PieceHashException if the piece SHA-1 hash does not match the hash from the meta-info file.
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] does not have [pieceIndex].
     */
    fun requestPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenCompose { value ->
            if (null == value) {
                throw IllegalArgumentException("requestPiece: infohash wasn't loaded")
            }
            if (activeSockets[infohash]?.containsKey(peer) != true) {
                throw IllegalArgumentException("requestPiece: peer is not known")
            }
            if (peersBitMap[infohash]?.get(peer)?.get(pieceIndex)?.equals(1.toByte()) != true) {
                throw IllegalArgumentException("requestPiece: peer doesn't have pieceIndex")
            }
            if(activePeers[infohash]!![peer]!!.peerChoking){
                throw PeerChokedException("requestPiece: peer is choking")
            }
            piecesStorage.read(infohash)
        }.thenCompose { piecesMapBytes ->
            val partLength = 2.0.pow(14).toInt()
            var requestLength = partLength
            val socket = activeSockets[infohash]!![peer]
            val piecesMap = Bencoder(piecesMapBytes as ByteArray).decodeData() as HashMap<Long, Piece>
            val pieceLength = piecesMap[pieceIndex]!!.length.toInt()
            val numParts = ceil(pieceLength / partLength.toDouble()).toInt()
            //if lastPartLength is not 0 then the last piece is smaller than 2^14.
            val lastPartLength = (pieceLength.rem(partLength))
            val requestedPiece = Piece(pieceIndex,pieceLength.toLong(),piecesMap[pieceIndex]!!.hashValue,ByteArray(pieceLength))
            for (i in 0 until numParts) {
                if (i == numParts - 1 && lastPartLength != 0) {
                    requestLength = lastPartLength
                }
                socket!!.getOutputStream().write(
                    WireProtocolEncoder.encode(6.toByte(), pieceIndex.toInt(), i * (partLength), requestLength)
                )

                val msgLenBytes = (socket.getInputStream().readNBytes(4))
                val msgLen = getMsgLength(msgLenBytes)

                if (msgLen > 0) {
                    val restOfMsg = socket.getInputStream().readNBytes(msgLen)
                    val msg = msgLenBytes.plus(restOfMsg)
                    val msgId = WireProtocolDecoder.messageId(msg)
                    //choke
                    if (msgId == 0.toByte()) {
                        handleChoke(infohash, peer)
                        throw PeerChokedException("requestPiece: peer is choking")
                    } else if (msgId == 1.toByte()) {
                        handleUnChoke(infohash, peer)
                        //have
                    } else if (msgId == 4.toByte()) {
                        handleHave(infohash, peer, msg)
                        //bitfield
                    } else if (msgId == 5.toByte()) {
                        handleBitField(infohash, peer, msg)
                        //request
                    } else if (msgId == 6.toByte()) {

                    }
                    //old response
                    //val response = socket.getInputStream().readNBytes(13 + partLength)
                    WireProtocolDecoder.decode(msg, 2).contents.copyInto(requestedPiece.data!!, i * (partLength))
                }
            }
            val md = MessageDigest.getInstance("SHA-1")
            val pieceHash = md.digest(requestedPiece.data!!)
            if(!requestedPiece.hashValue.contentEquals(pieceHash)){
                throw PieceHashException("requestPiece: piece is not correct")
            }
            piecesStorage.write(infohash, Bencoder.encodeStr(requestedPiece).toByteArray())
            //TODO send have?, update files? (compelted , downloaded, etc)
        }.exceptionally { exc ->
            if (!(exc.cause is IllegalArgumentException ||  exc.cause is PeerChokedException)) {
                activeSockets[infohash]!!.remove(peer)
                activePeers[infohash]!!.remove(peer)
                throw PeerConnectException("requestPiece: peer disconnected")
            }
            throw exc
        }
    }

    /**
     * Send piece number [pieceIndex] of the [infohash] torrent to [peer].
     *
     * Upload a complete piece (as much as possible) by sending a series of piece messages. This method finishes
     * successfully (i.e., the [CompletableFuture] is completed) if [peer] hasn't requested another subset of the piece
     * in 100ms.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] did not request [pieceIndex].
     */
    fun sendPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {
        return announcesStorage.read(infohash).thenCompose { announces ->
            if (null == announces) throw IllegalArgumentException("sendPiece: infohash isn't loaded")
            // TODO activePeers or KnownPeers???
            peersStorage.read(infohash)
        }.thenCompose { checkedPeer ->
            if (null == checkedPeer) throw IllegalArgumentException("sendPiece: peer is not known")
            // TODO make sure the line below deals with null as well
            if (peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.isEmpty() != false) {
                throw IllegalArgumentException("per did not request the specified piece")
            }
            piecesStorage.read(infohash).thenApply { piecesMapBytes ->
                val piecesMap = Bencoder(piecesMapBytes as ByteArray).decodeData() as HashMap<Long, Piece>

                val partLength = 2.0.pow(14).toInt()
                var requestLength = partLength
                val socket = activeSockets[infohash]!![peer]!! // TODO Should be checked?
                val pieceLength = piecesMap[pieceIndex]!!.length.toInt()
                val numParts = ceil(pieceLength / partLength.toDouble()).toLong()
                val pieceData = piecesMap[pieceIndex]!!.data
                //if lastPartLength is not 0 then the last piece is smaller than 2^14.
                val lastPartLength = (pieceLength.rem(partLength))

                // Deal with existing requests.
                peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.forEach { partIndex ->
                    val offset = (partLength * partIndex).toInt()
                    if (partIndex == (numParts - 1) && lastPartLength != 0) {
                        requestLength = lastPartLength
                    }
                    val partData = pieceData!!.drop(offset).take(requestLength).toByteArray()
                    socket.getOutputStream().write(
                        WireProtocolEncoder.encode(7.toByte(), partData, pieceIndex.toInt(), offset, partLength))
                    // Remove the partIndex from the requests pieces map.
                    peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.remove(partIndex)
                }

                // Wait 100ms and then check for more requests.
                socket.soTimeout = 100
                sleep(100)
                var pendingRequests = 0
                do {
                    val msgLenBytes = socket.getInputStream().readNBytes(4)
                    val msgLen = getMsgLength(msgLenBytes)

                    if (msgLen > 0) {
                        val restOfMsg = socket.getInputStream().readNBytes(msgLen)
                        val msg = msgLenBytes.plus(restOfMsg)
                        val msgId = WireProtocolDecoder.messageId(msg)
                        if (msgId == 0.toByte()) {
                            // choke
                            handleChoke(infohash, peer)
                        } else if (msgId == 1.toByte()) {
                            // unchoke
                            handleUnChoke(infohash, peer)
                        } else if (msgId == 4.toByte()) {
                            // have
                            handleHave(infohash, peer, msg)
                        } else if (msgId == 5.toByte()) {
                            // bitfield
                            handleBitField(infohash, peer, msg)
                        } else if (msgId == 6.toByte()) {
                            // request
                            handleRequest(infohash, peer, msg)
                        }
                    }
                    // Check if there are new pending requests. If there are, handle them. Otherwise, stop the method.
                    pendingRequests = peersRequests[infohash]?.get(peer)?.get(pieceIndex)?.size ?: 0
                } while (pendingRequests > 0)
            }
        }.exceptionally { exc ->
            if (exc.cause is IllegalArgumentException) {
                throw exc
            }

            // Socket related exception - assuming we received no messages, therefore complete future successfuly.
            CompletableFuture.completedFuture(Unit)
        }
    }

    /**
     * List pieces that are currently available for download immediately.
     *
     * That is, pieces that:
     * 1. We don't have yet,
     * 2. A peer we're connected to does have,
     * 3. That peer is not choking us.
     *
     * Returns a mapping from connected, unchoking, interesting peer to a list of maximum length [perPeer] of pieces
     * that meet the above criteria. The lists may overlap (contain the same piece indices). The pieces in the list
     * should begin at [startIndex] and continue sequentially in a cyclical manner up to `[startIndex]-1`.
     *
     * For example, there are 3 pieces, we don't have any of them, and we are connected to PeerA that has piece 1 and
     * 2 and is not choking us. So, `availablePieces(infohash, 3, 2) => {PeerA: [2, 1]}`.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of [perPeer] pieces that can be downloaded from it, starting at [startIndex].
     */
    fun availablePieces(
        infohash: String,
        perPeer: Long,
        startIndex: Long
    ): CompletableFuture<Map<KnownPeer, List<Long>>> {
        return piecesStorage.read(infohash).thenApply { piecesMapBytes ->
            if(null == piecesMapBytes) {
                throw IllegalArgumentException("availablePieces infohash not loaded")
            }
            val setNeededPieces = hashSetOf<Long>()
            val availablePiecesMap = hashMapOf<KnownPeer, List<Long>>()
            val piecesMap = Bencoder(piecesMapBytes).decodeData() as HashMap<Long, Piece>
            piecesMap.forEach { entry ->
                if (null == entry.value.data) {
                    setNeededPieces.add(entry.key)
                }
            }
            val connectedPeers = activePeers[infohash]?.values ?: listOf<ConnectedPeer>()
            for (peer in connectedPeers.filter { peer -> !peer.peerChoking }) {
                val peerPieces = listOf<Long>()
                for (i in setNeededPieces.filter { x -> x >= startIndex }.sorted()) {

                }
            }
            availablePiecesMap
            }
        /*  for(piece in piecesMap.values){
            if(null == piece.data){
                if(!peersBitMap.isNullOrEmpty()){
                    if(!peersBitMap[infohash].isNullOrEmpty()){
                        peersBitMap[infohash]!!.forEach { map ->
                            if(!activePeers[infohash]!![map.key]!!.peerChoking){
                                if(peersBitMap[infohash]!![map.key]!![piece.index] == 1.toByte()){

                                }
                            }
                        }
                    }
                }
            }
        }*/
    }

    /**
     * List pieces that have been requested by (unchoked) peers.
     *
     * If a a peer sent us a request message for a subset of a piece (possibly more than one), that piece will be listed
     * here.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of unique pieces that it has requested.
     */
    fun requestedPieces(
        infohash: String
    ): CompletableFuture<Map<KnownPeer, List<Long>>> = TODO("Implement me!")

    /**
     * Return the downloaded files for torrent [infohash].
     *
     * Partially downloaded files are allowed. Bytes that haven't been downloaded yet are zeroed.
     * File names are given including path separators, e.g., "foo/bar/file.txt".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from file name to file contents.
     */
    fun files(infohash: String): CompletableFuture<Map<String, ByteArray>> = TODO("Implement me!")

    /**
     * Load files into the client.
     *
     * If [files] has extra files, they are ignored. If it is missing a file, it is treated as all zeroes. If file
     * contents are too short, the file is padded with zeroes. If the file contents are too long, they are truncated.
     *
     * @param files A mapping from filename to file contents.
     * @throws IllegalArgumentException if [infohash] is not loaded,
     */
    fun loadFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Compare SHA-1 hash for the loaded pieces of torrent [infohash] against the meta-info file. If a piece fails hash
     * checking, it is zeroed and marked as not downloaded.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return True if all the pieces have been downloaded and passed hash checking, false otherwise.
     */
    fun recheck(infohash: String): CompletableFuture<Boolean> = TODO("Implement me!")

    private fun handleChoke(infohash: String, peer: KnownPeer) {
        activePeers[infohash]!![peer]!!.peerChoking = true
    }

    private fun handleUnChoke(infohash: String, peer: KnownPeer) {
        activePeers[infohash]!![peer]!!.peerChoking = false
    }

    private fun handleHave(infohash: String,peer: KnownPeer, msg: ByteArray) {
        val haveMsg = WireProtocolDecoder.decode(msg,1)
        val pieceIndex = haveMsg.ints[0]
        val torrentPeerBitMap = peersBitMap[infohash] ?: hashMapOf()
        val peerBitMap = torrentPeerBitMap[peer] ?: hashMapOf()
        peerBitMap[pieceIndex.toLong()] = 1.toByte()
        torrentPeerBitMap[peer] = peerBitMap
        peersBitMap[infohash] = torrentPeerBitMap
    }

    private fun handleBitField(infohash: String,peer: KnownPeer, msg: ByteArray) {
        val bitFieldmsg = WireProtocolDecoder.decode(msg,1)
        val bitfield = bitFieldmsg.contents
        val torrentPeerBitMap = peersBitMap[infohash] ?: hashMapOf()
        val peerBitMap = torrentPeerBitMap[peer] ?: hashMapOf()
        bitfield.forEachIndexed { i, byte ->
            peerBitMap[i.toLong()] = byte
        }
        torrentPeerBitMap[peer] = peerBitMap
        peersBitMap[infohash] = torrentPeerBitMap
    }

    private fun handleRequest(infohash: String,peer: KnownPeer, msg: ByteArray){


    }

}