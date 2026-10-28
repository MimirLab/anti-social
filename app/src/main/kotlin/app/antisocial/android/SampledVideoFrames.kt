/* anti social, GPL-3.0-or-later. Descriptor decoder output carries no source URL. */
package app.antisocial.android

import app.antisocial.core.SampledVideoScene

internal data class SampledVideoFrames(val durationMs:Long,val scenes:List<SampledVideoScene>)
