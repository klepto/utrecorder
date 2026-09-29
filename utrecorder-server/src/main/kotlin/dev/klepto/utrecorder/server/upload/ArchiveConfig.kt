package dev.klepto.utrecorder.server.upload

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader

/**
 * Settings of the Internet Archive account that media is uploaded to.
 *
 * Use the constructor. A property that is not provided takes the value its `server.archive.*` key configures, or else
 * its literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `server.archive.*` keys.
 * @property accessKey The S3 access key of the account, or `null` if it is not configured.
 * @property secretKey The S3 secret key of the account, or `null` if it is not configured.
 * @property collection The collection every uploaded item is placed in.
 * @property prefix The prefix of every item identifier, followed by a hyphen and the item id.
 */
class ArchiveConfig(
    reader: ConfigReader = SystemConfigReader(),
    val accessKey: String? = reader["server.archive.access_key"],
    val secretKey: String? = reader["server.archive.secret_key"],
    val collection: String = reader.get("server.archive.collection", "test_collection"),
    val prefix: String = reader.get("server.archive.prefix", "ut99-bt"),
)
