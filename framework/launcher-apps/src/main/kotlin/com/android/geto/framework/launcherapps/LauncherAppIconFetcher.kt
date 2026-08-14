/*
 *
 *   Copyright 2023 Einstein Blanco
 *
 *   Licensed under the GNU General Public License v3.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       https://www.gnu.org/licenses/gpl-3.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package com.android.geto.framework.launcherapps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import com.android.geto.domain.model.LauncherAppIcon

internal class LauncherAppIconKeyer : Keyer<LauncherAppIcon> {
    override fun key(data: LauncherAppIcon, options: Options): String = "launcher-app-icon:${data.componentName}:${data.lastUpdateTime}"
}

internal class LauncherAppIconFetcher(
    private val context: Context,
    private val data: LauncherAppIcon,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val componentName = ComponentName.unflattenFromString(data.componentName) ?: return null
        val drawable = try {
            context.packageManager.getActivityIcon(componentName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val fallbackSize = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val width = (options.size.width as? Dimension.Pixels)?.px
            ?: drawable.intrinsicWidth.takeIf { it > 0 }
            ?: fallbackSize
        val height = (options.size.height as? Dimension.Pixels)?.px
            ?: drawable.intrinsicHeight.takeIf { it > 0 }
            ?: fallbackSize

        return DrawableResult(
            drawable = drawable.renderAtSize(width = width, height = height),
            isSampled = width < drawable.intrinsicWidth || height < drawable.intrinsicHeight,
            dataSource = DataSource.DISK,
        )
    }

    private fun Drawable.renderAtSize(width: Int, height: Int): Drawable {
        val safeWidth = width.coerceIn(1, MAX_ICON_SIZE_PX)
        val safeHeight = height.coerceIn(1, MAX_ICON_SIZE_PX)
        val bitmap = createBitmap(safeWidth, safeHeight)
        val canvas = Canvas(bitmap)
        val previousBounds = Rect(bounds)
        setBounds(0, 0, safeWidth, safeHeight)
        draw(canvas)
        bounds = previousBounds
        return bitmap.toDrawable(context.resources)
    }

    class Factory(
        private val context: Context,
    ) : Fetcher.Factory<LauncherAppIcon> {
        override fun create(
            data: LauncherAppIcon,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = LauncherAppIconFetcher(context, data, options)
    }

    private companion object {
        const val MAX_ICON_SIZE_PX = 512
    }
}
