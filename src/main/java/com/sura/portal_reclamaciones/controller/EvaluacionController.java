package com.sura.portal_reclamaciones.controller;

import com.sura.portal_reclamaciones.dto.importacion.ResultadoEvaluacionLote;
import com.sura.portal_reclamaciones.service.MotorReglasService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

// Este controlador permite ejecutar el motor sin mezclar las reglas
// de negocio con el código que atiende las peticiones HTTP.
@RestController
@RequestMapping("/api/casos")
public class EvaluacionController {

    private static final Logger log =
            LoggerFactory.getLogger(EvaluacionController.class);

    private final MotorReglasService motorReglas;

    public EvaluacionController(MotorReglasService motorReglas) {
        this.motorReglas = motorReglas;
    }

    // POST /api/casos/evaluar
    // Procesa los casos pendientes y permite reevaluar los que siguen
    // en estados permitidos, sin tocar los que ya tomó un analista.
    @PostMapping("/evaluar")
    public Mono<ResponseEntity<ResultadoEvaluacionLote>> evaluarCasos() {

        // El motor usa JPA, que realiza operaciones bloqueantes.
        // Por eso lo ejecutamos en un hilo apropiado y no en el de Netty.
        return Mono.fromCallable(motorReglas::evaluarPendientes)
                .subscribeOn(Schedulers.boundedElastic())
                .map(resultado -> ResponseEntity.ok(resultado))
                .onErrorResume(error -> {
                    // El detalle técnico queda en el log del servidor,
                    // no en la respuesta que recibe el usuario.
                    log.error("Falló la evaluación de las reclamaciones.", error);

                    ResultadoEvaluacionLote respuesta =
                            new ResultadoEvaluacionLote(
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    "No se pudo completar la evaluación. "
                                            + "Revisa los registros del servidor.",
                                    java.util.List.of(
                                            "La evaluación no terminó correctamente."
                                    )
                            );

                    return Mono.just(
                            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .body(respuesta)
                    );
                });
    }
}