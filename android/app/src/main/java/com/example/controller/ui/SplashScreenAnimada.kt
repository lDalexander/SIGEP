package com.example.controller.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controller.R
import kotlinx.coroutines.delay

// Mismos colores que la splash XML anterior (fondo blanco, texto negro / gris).
private val FondoSplash = Color.White
private val TextoPrincipal = Color.Black
private val TextoAtenuado = Color(0xFF6B6B6B)

private val TamanoGota = 96.dp

/**
 * Splash en 4 fases:
 *  1 (0.0–0.7 s)  nombre largo + SIGEP
 *  2 (0.7–1.3 s)  se va el nombre largo, SIGEP crece con rebote
 *  3 (1.3–2.5 s)  SIGEP desaparece, cae la "gota" (logo Detcuador)
 *  4 (2.5–4.5 s)  la gota se aparta a la izquierda y aparece la firma
 *
 * [version] se pinta bajo la firma: en planta es la forma rápida de ver qué APK tiene
 * la tablet (ver VersionApp.kt). null = no se muestra.
 */
@Composable
fun SplashScreenAnimada(version: String? = null, onTerminado: () -> Unit) {
    var faseAnimacion by remember { mutableIntStateOf(0) }
    val terminar by rememberUpdatedState(onTerminado)

    LaunchedEffect(Unit) {
        faseAnimacion = 1
        delay(700)
        faseAnimacion = 2
        delay(600)
        faseAnimacion = 3
        delay(1_200)
        faseAnimacion = 4
        delay(2_000)
        terminar()
    }

    // ---- Fases 1 y 2: textos ----
    val alphaTextoLargo by animateFloatAsState(
        targetValue = if (faseAnimacion == 1) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "alphaTextoLargo"
    )
    val escalaSigep by animateFloatAsState(
        targetValue = if (faseAnimacion >= 2) 1.5f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "escalaSigep"
    )
    val alphaSigep by animateFloatAsState(
        targetValue = if (faseAnimacion in 1..2) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "alphaSigep"
    )

    // ---- Fase 3: la gota ----
    val alphaGota by animateFloatAsState(
        targetValue = if (faseAnimacion >= 3) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "alphaGota"
    )
    val gotaOffsetY by animateDpAsState(
        targetValue = if (faseAnimacion >= 3) 0.dp else (-300).dp,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "gotaOffsetY"
    )

    // ---- Fase 4: desplazamiento y firma ----
    val gotaOffsetX by animateDpAsState(
        targetValue = if (faseAnimacion >= 4) (-80).dp else 0.dp,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "gotaOffsetX"
    )
    val alphaFirma by animateFloatAsState(
        targetValue = if (faseAnimacion >= 4) 1f else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = 150),
        label = "alphaFirma"
    )
    // El borde izquierdo de la firma arranca detrás de la gota centrada y termina
    // justo a la derecha de la gota ya desplazada (-80dp + 48dp de medio ancho = -32dp).
    val firmaOffsetX by animateDpAsState(
        targetValue = if (faseAnimacion >= 4) (-16).dp else (-60).dp,
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing),
        label = "firmaOffsetX"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FondoSplash),
        contentAlignment = Alignment.Center
    ) {
        // Fases 1–2
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = "Sistema Integrado de Gestión de Empaque y Producción",
                color = TextoAtenuado,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = alphaTextoLargo }
            )
            Spacer(Modifier.height(16.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    scaleX = escalaSigep
                    scaleY = escalaSigep
                    alpha = alphaSigep
                }
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_sigep),
                    contentDescription = "Logo SIGEP",
                    modifier = Modifier.size(96.dp)
                )
                Text(
                    text = "SIGEP",
                    color = TextoPrincipal,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            }
        }

        // Fase 4 — firma. Se dibuja ANTES que la gota para quedar detrás de ella.
        // La caja de ancho 0 en el centro fija el borde izquierdo del texto en el centro
        // de la pantalla; el texto crece hacia la derecha sin recortarse.
        Box(Modifier.width(0.dp)) {
            Column(
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .offset(x = firmaOffsetX)
                    .graphicsLayer { alpha = alphaFirma }
            ) {
                Text(
                    text = "Desarrollado por John Garcia",
                    color = TextoPrincipal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    softWrap = false
                )
                Text(
                    text = "DETCUADOR S.A.",
                    color = TextoPrincipal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    softWrap = false
                )
                if (version != null) {
                    Text(
                        text = "Versión $version",
                        color = TextoAtenuado,
                        fontSize = 10.sp,
                        softWrap = false
                    )
                }
            }
        }

        // Fases 3–4 — la gota
        Image(
            painter = painterResource(R.drawable.logo_detcuador),
            contentDescription = "Logo Detcuador",
            modifier = Modifier
                .offset(x = gotaOffsetX, y = gotaOffsetY)
                .size(TamanoGota)
                .graphicsLayer { alpha = alphaGota }
        )
    }
}

@Preview(widthDp = 800, heightDp = 1280)
@Composable
private fun SplashScreenAnimadaPreview() {
    SplashScreenAnimada(version = "1.7.1", onTerminado = {})
}
