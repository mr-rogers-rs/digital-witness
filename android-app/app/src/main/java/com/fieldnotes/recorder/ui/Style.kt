package com.fieldnotes.recorder.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/** dp → px for the given context. */
fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/**
 * Small programmatic design system ("field tool" aesthetic) shared by the
 * onboarding, home, and Nextcloud-setup screens.
 *
 * Deliberately dependency-free: no XML, no Compose, no Material components
 * beyond what's already on the classpath. Every screen composes the same
 * calm, high-contrast, light/dark-aware tokens and view factories so the app
 * reads as one coherent surface. Colors are resolved per-call from the current
 * night-mode configuration, so screens themed via [Ui.screen] adapt to the
 * system theme without any per-widget color code.
 */
object Ui {

    data class Palette(
        val bg: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val accentSoft: Int,
        val onAccent: Int,
        val divider: Int,
        val fieldBg: Int,
        val fieldStroke: Int,
        val danger: Int,
        val success: Int,
        val warning: Int,
    )

    private val LIGHT = Palette(
        bg = 0xFFF4F6F8.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceAlt = 0xFFEDF0F3.toInt(),
        textPrimary = 0xFF1A1C1E.toInt(),
        textSecondary = 0xFF5B626B.toInt(),
        accent = 0xFF1E7A6B.toInt(),
        accentSoft = 0xFFDCEEEA.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        divider = 0xFFE2E5E9.toInt(),
        fieldBg = 0xFFFFFFFF.toInt(),
        fieldStroke = 0xFFCFD4DA.toInt(),
        danger = 0xFFC0392B.toInt(),
        success = 0xFF2E7D48.toInt(),
        warning = 0xFFB26A00.toInt(),
    )

    private val DARK = Palette(
        bg = 0xFF111417.toInt(),
        surface = 0xFF1B1E22.toInt(),
        surfaceAlt = 0xFF23272C.toInt(),
        textPrimary = 0xFFECEEF1.toInt(),
        textSecondary = 0xFF9BA2AA.toInt(),
        accent = 0xFF39A491.toInt(),
        accentSoft = 0xFF16302B.toInt(),
        onAccent = 0xFF061110.toInt(),
        divider = 0xFF2A2F34.toInt(),
        fieldBg = 0xFF23272C.toInt(),
        fieldStroke = 0xFF3A4147.toInt(),
        danger = 0xFFE05B4B.toInt(),
        success = 0xFF4FB477.toInt(),
        warning = 0xFFD9922E.toInt(),
    )

    fun palette(ctx: Context): Palette {
        val night = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) DARK else LIGHT
    }

    enum class Btn { PRIMARY, SECONDARY, DANGER, GHOST }

    // ── Drawables ────────────────────────────────────────────────────────────

    fun rounded(color: Int, radiusPx: Int, strokeColor: Int? = null, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
            if (strokeColor != null && strokePx > 0) setStroke(strokePx, strokeColor)
        }

    private fun ripple(base: GradientDrawable, rippleColor: Int): RippleDrawable {
        // A separate mask drawable (not `base` reused) clips the ripple to the
        // rounded shape without sharing a single Drawable instance across slots.
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt()); cornerRadius = base.cornerRadius
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask)
    }

    // ── Screen scaffold ──────────────────────────────────────────────────────

    /**
     * A themed scroll container + its vertical content column. Add content to
     * the returned [LinearLayout]; pass the [ScrollView] to setContentView.
     */
    fun screen(ctx: Context): Pair<ScrollView, LinearLayout> {
        val p = palette(ctx)
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(p.bg)
            isFillViewport = true
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(24), ctx.dp(20), ctx.dp(28))
        }
        scroll.addView(col, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return scroll to col
    }

    fun card(ctx: Context, topMargin: Int = 12): LinearLayout {
        val p = palette(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.surface, ctx.dp(16), p.divider, ctx.dp(1))
            val pad = ctx.dp(18)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                this.topMargin = ctx.dp(topMargin)
            }
        }
    }

    fun spacer(ctx: Context, height: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(height)) }

    fun divider(ctx: Context): View {
        val p = palette(ctx)
        return View(ctx).apply {
            setBackgroundColor(p.divider)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(1)).apply {
                topMargin = ctx.dp(12); bottomMargin = ctx.dp(12)
            }
        }
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    fun h1(ctx: Context, text: CharSequence): TextView {
        val p = palette(ctx)
        return TextView(ctx).apply {
            this.text = text
            textSize = 26f
            setTextColor(p.textPrimary)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    fun title(ctx: Context, text: CharSequence): TextView {
        val p = palette(ctx)
        return TextView(ctx).apply {
            this.text = text
            textSize = 18f
            setTextColor(p.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    /** Small, secondary, letter-spaced section label. */
    fun sectionLabel(ctx: Context, text: CharSequence): TextView {
        val p = palette(ctx)
        return TextView(ctx).apply {
            this.text = text.toString().uppercase()
            textSize = 12f
            setTextColor(p.textSecondary)
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, ctx.dp(4), 0, ctx.dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    fun body(ctx: Context, text: CharSequence): TextView {
        val p = palette(ctx)
        return TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(p.textPrimary)
            setLineSpacing(ctx.dp(4).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    fun caption(ctx: Context, text: CharSequence): TextView {
        val p = palette(ctx)
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(p.textSecondary)
            setLineSpacing(ctx.dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    // ── Buttons ──────────────────────────────────────────────────────────────

    fun button(ctx: Context, text: CharSequence, kind: Btn = Btn.PRIMARY): Button {
        val p = palette(ctx)
        val radius = ctx.dp(12)
        val (bg, fg, stroke) = when (kind) {
            Btn.PRIMARY -> Triple(p.accent, p.onAccent, null)
            Btn.DANGER -> Triple(p.danger, 0xFFFFFFFF.toInt(), null)
            Btn.SECONDARY -> Triple(p.surfaceAlt, p.textPrimary, p.fieldStroke)
            Btn.GHOST -> Triple(0x00000000, p.accent, null)
        }
        val base = rounded(bg, radius, stroke, if (stroke != null) ctx.dp(1) else 0)
        val rippleColor = if (kind == Btn.GHOST || kind == Btn.SECONDARY) 0x33000000 else 0x40FFFFFF
        return Button(ctx).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ripple(base, rippleColor)
            stateListAnimator = null
            minHeight = ctx.dp(52)
            setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = ctx.dp(10)
            }
        }
    }

    // ── Inputs ───────────────────────────────────────────────────────────────

    fun field(ctx: Context, hint: CharSequence): EditText {
        val p = palette(ctx)
        return EditText(ctx).apply {
            this.hint = hint
            setHintTextColor(p.textSecondary)
            setTextColor(p.textPrimary)
            textSize = 16f
            setSingleLine(true)
            background = rounded(p.fieldBg, ctx.dp(10), p.fieldStroke, ctx.dp(1))
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = ctx.dp(6)
            }
        }
    }

    /** A labelled input group (caption label + styled field), appended to [parent]. */
    fun labeledField(ctx: Context, parent: LinearLayout, label: CharSequence, hint: CharSequence): EditText {
        parent.addView(caption(ctx, label).apply {
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                .also { it.topMargin = ctx.dp(12); layoutParams = it }
        })
        val f = field(ctx, hint)
        parent.addView(f)
        return f
    }

    /** A soft, accent-tinted info note. */
    fun note(ctx: Context, text: CharSequence): TextView {
        val p = palette(ctx)
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(p.textSecondary)
            setLineSpacing(ctx.dp(3).toFloat(), 1f)
            background = rounded(p.accentSoft, ctx.dp(10))
            val pad = ctx.dp(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = ctx.dp(10)
            }
        }
    }

    fun checkbox(ctx: Context, text: CharSequence, checked: Boolean): CheckBox {
        val p = palette(ctx)
        return CheckBox(ctx).apply {
            this.text = text
            isChecked = checked
            textSize = 15f
            setTextColor(p.textPrimary)
            buttonTintList = ColorStateList.valueOf(p.accent)
            setPadding(ctx.dp(8), ctx.dp(6), 0, ctx.dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = ctx.dp(2)
            }
        }
    }

    fun radio(ctx: Context, text: CharSequence): RadioButton {
        val p = palette(ctx)
        return RadioButton(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(p.textPrimary)
            buttonTintList = ColorStateList.valueOf(p.accent)
            setPadding(ctx.dp(8), ctx.dp(8), 0, ctx.dp(8))
            id = View.generateViewId()
        }
    }

    /** A colored status dot • + label row (used on the home screen). */
    fun statusRow(ctx: Context, dotColor: Int, text: CharSequence): LinearLayout {
        val p = palette(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(ctx).apply {
                background = rounded(dotColor, ctx.dp(6))
                layoutParams = LinearLayout.LayoutParams(ctx.dp(12), ctx.dp(12)).apply {
                    rightMargin = ctx.dp(10)
                }
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 16f
                setTextColor(p.textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
    }
}
