package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.DeliveryStage
import com.example.model.LiveOrderState
import com.example.ui.theme.PulseAmber
import com.example.ui.theme.PulseMint

@Composable
fun NowBarPreviewCard(
  order: LiveOrderState,
  nowMillis: Long,
  onDismiss: () -> Unit,
  onAdvanceSimulation: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val brandColor = Color(order.accentColorHex)
  val stageAccent =
    when (order.stage) {
      DeliveryStage.PREPARING -> PulseAmber
      DeliveryStage.DELIVERING -> brandColor
      DeliveryStage.DELIVERED -> PulseMint
    }

  val animatedProgress by
    animateFloatAsState(
      targetValue = (order.progress.coerceIn(0, 100) / 100f),
      animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
      label = "order_progress_anim",
    )

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
      ),
    border =
      androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = stageAccent.copy(alpha = 0.35f),
      ),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Top row: App badge + Status Bar Pill preview + close button
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Box(
            modifier =
              Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(brandColor),
          )
          Text(
            text = order.appName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          // Live Status Bar Capsule Preview (Android 16 / One UI 8+ Chip)
          StatusChipPill(
            text = order.chipShortText,
            stage = order.stage,
            accentColor = stageAccent,
          )
        }

        IconButton(
          onClick = onDismiss,
          modifier =
            Modifier
              .minimumInteractiveComponentSize()
              .testTag("dismiss_order_${order.orderKey}"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "关闭实时窗通知",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Notification title & extracted body
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = order.title,
          style = MaterialTheme.typography.titleLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = order.contentText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      // Segmented ProgressStyle Bar Preview matching Android 16 Notification.ProgressStyle
      SegmentedProgressTrack(
        progressFraction = animatedProgress,
        brandColor = brandColor,
        modifier = Modifier.fillMaxWidth(),
      )

      // Telemetry footer: stage, percentage, matched regex, auto-dismiss countdown
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "${order.stage.label} · ${order.progress}%",
            style = MaterialTheme.typography.labelMedium,
            color = stageAccent,
          )
          Text(
            text = order.matchedRuleName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        val autoDismissAt = order.autoDismissAtMillis
        if (order.stage == DeliveryStage.DELIVERED && autoDismissAt != null) {
          val remainSec = ((autoDismissAt - nowMillis) / 1000L).coerceAtLeast(0L)
          Surface(
            color = PulseMint.copy(alpha = 0.16f),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "${remainSec}s 后销毁",
              style = MaterialTheme.typography.labelMedium,
              color = PulseMint,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        } else {
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (order.isPromotedOngoing) PulseMint else MaterialTheme.colorScheme.primary,
              )
              Text(
                text = if (order.isPromotedOngoing) "NowBar 已激活" else "Live 通知已推送",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }

      if (order.promotionDiagnostic.isNotBlank()) {
        Text(
          text = order.promotionDiagnostic,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
      }

      // Action buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (onAdvanceSimulation != null && order.stage != DeliveryStage.DELIVERED) {
          FilledTonalButton(
            onClick = onAdvanceSimulation,
            modifier =
              Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("advance_order_${order.orderKey}"),
          ) {
            Icon(
              imageVector = Icons.Rounded.SkipNext,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("推进下一阶段")
          }
        }
        OutlinedButton(
          onClick = onDismiss,
          modifier =
            Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("cancel_live_notif_${order.orderKey}"),
        ) {
          Text("立即关闭通知")
        }
      }
    }
  }
}

@Composable
fun StatusChipPill(
  text: String,
  stage: DeliveryStage,
  accentColor: Color,
  modifier: Modifier = Modifier,
) {
  val icon =
    when (stage) {
      DeliveryStage.PREPARING -> Icons.Rounded.Fastfood
      DeliveryStage.DELIVERING -> Icons.Rounded.DeliveryDining
      DeliveryStage.DELIVERED -> Icons.Rounded.CheckCircle
    }

  Row(
    modifier =
      modifier
        .clip(RoundedCornerShape(50))
        .background(accentColor.copy(alpha = 0.2f))
        .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(50))
        .padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = accentColor,
      modifier = Modifier.size(14.dp),
    )
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = accentColor,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
fun SegmentedProgressTrack(
  progressFraction: Float,
  brandColor: Color,
  modifier: Modifier = Modifier,
) {
  val prepColor = PulseAmber
  val arriveColor = PulseMint
  val trackBg = MaterialTheme.colorScheme.surfaceContainerHigh

  Canvas(
    modifier =
      modifier
        .height(22.dp)
        .padding(vertical = 4.dp),
  ) {
    val trackHeight = 8.dp.toPx()
    val centerY = size.height / 2f
    val top = centerY - trackHeight / 2f
    val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
    val totalW = size.width

    // Background track
    drawRoundRect(
      color = trackBg,
      topLeft = Offset(0f, top),
      size = Size(totalW, trackHeight),
      cornerRadius = radius,
    )

    // Active progress segmented fill (15% Prep, 75% Delivery, 10% Arrive)
    val fillW = (totalW * progressFraction.coerceIn(0f, 1f))
    val seg1End = totalW * 0.15f
    val seg2End = totalW * 0.90f

    if (fillW > 0f) {
      val part1 = minOf(fillW, seg1End)
      drawRoundRect(
        color = prepColor,
        topLeft = Offset(0f, top),
        size = Size(part1, trackHeight),
        cornerRadius = radius,
      )
    }
    if (fillW > seg1End) {
      val part2 = minOf(fillW, seg2End) - seg1End
      drawRect(
        color = brandColor,
        topLeft = Offset(seg1End, top),
        size = Size(part2, trackHeight),
      )
    }
    if (fillW > seg2End) {
      val part3 = fillW - seg2End
      drawRoundRect(
        color = arriveColor,
        topLeft = Offset(seg2End, top),
        size = Size(part3, trackHeight),
        cornerRadius = radius,
      )
    }

    // Milestone points at 15% and 90%
    listOf(0.15f to prepColor, 0.90f to arriveColor).forEach { (fraction, color) ->
      val x = totalW * fraction
      drawCircle(
        color = Color.White,
        radius = 5.dp.toPx(),
        center = Offset(x, centerY),
      )
      drawCircle(
        color = color,
        radius = 3.dp.toPx(),
        center = Offset(x, centerY),
      )
    }

    // Tracker dot at current progress position
    val clampedX = fillW.coerceIn(7.dp.toPx(), (totalW - 7.dp.toPx()).coerceAtLeast(7.dp.toPx()))
    drawCircle(
      color = Color.White,
      radius = 8.dp.toPx(),
      center = Offset(clampedX, centerY),
    )
    drawCircle(
      color =
        when {
          progressFraction >= 0.99f -> arriveColor
          progressFraction >= 0.15f -> brandColor
          else -> prepColor
        },
      radius = 5.dp.toPx(),
      center = Offset(clampedX, centerY),
    )
  }
}
