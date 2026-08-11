package com.medtroniclabs.microcoaching

import androidx.core.content.FileProvider

/**
 * Dedicated [FileProvider] subclass for the SDK.
 *
 * A host app may declare its own `androidx.core.content.FileProvider` in its manifest
 * (SPICE `uhis-dev` does, with authority `${applicationId}.provider`). The manifest
 * merger keys `<provider>` nodes by `android:name`, so two providers that both use the
 * bare `androidx.core.content.FileProvider` class collapse into a single node and the
 * build fails with an authorities/`file_paths` conflict.
 *
 * Subclassing gives the SDK's provider a distinct component name
 * (`com.medtroniclabs.microcoaching.CoachingFileProvider`), so it coexists with any
 * host FileProvider as a separate component with its own
 * `${applicationId}.microcoaching.provider` authority. Runtime lookups via
 * `FileProvider.getUriForFile(context, authority, file)` resolve by authority and are
 * unaffected by the class name.
 */
class CoachingFileProvider : FileProvider()
