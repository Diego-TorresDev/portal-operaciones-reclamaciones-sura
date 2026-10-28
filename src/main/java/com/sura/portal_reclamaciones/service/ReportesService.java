
package com.sura.portal_reclamaciones.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sura.portal_reclamaciones.entity.CaseEvaluation;
import com.sura.portal_reclamaciones.entity.ClaimCase;
import com.sura.portal_reclamaciones.repository.CaseEvaluationRepository;
import com.sura.portal_reclamaciones.repository.ClaimCaseRepository;

@Service
public class ReportesService {

    private static final String[] COLUMNAS_CSV = {
            "ID_Caso",
            "Decision",
            "Reglas_Incumplidas",
            "Motivo",
            "Prioridad",
            "Estado_Actual",
            "Fecha_Limite_ANS",
            "Semaforo_ANS"
    };

    private final ClaimCaseRepository casos;
    private final CaseEvaluationRepository evaluaciones;

    public ReportesService(
            ClaimCaseRepository casos,
            CaseEvaluationRepository evaluaciones) {

        this.casos = casos;
        this.evaluaciones = evaluaciones;
    }

    /**
     * Calcula los indicadores sobre el estado actual de las reclamaciones.
     * Las cifras utilizan los mismos estados que las bandejas operativas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerResumen() {

        List<ClaimCase> todos = casos.findAll();
        Map<Long, CaseEvaluation> evaluacionesActuales =
                cargarEvaluacionesActuales();

        long total = todos.size();
        long automaticos = 0;
        long manuales = 0;
        long casosPendientesPago = 0;

        BigDecimal valorAPagar = BigDecimal.ZERO;

        Map<String, Long> porPrioridad = new LinkedHashMap<>();
        porPrioridad.put("ALTA", 0L);
        porPrioridad.put("MEDIA", 0L);
        porPrioridad.put("BAJA", 0L);
        porPrioridad.put("SIN_PRIORIDAD", 0L);

        Map<String, Long> porEstado = new TreeMap<>();
        Map<String, Long> porSemaforo = new LinkedHashMap<>();
        porSemaforo.put("VERDE", 0L);
        porSemaforo.put("AMARILLO", 0L);
        porSemaforo.put("ROJO", 0L);
        porSemaforo.put("CUMPLIDO", 0L);
        porSemaforo.put("SIN_SEMAFORO", 0L);

        for (ClaimCase caso : todos) {

            CaseEvaluation evaluacion =
                    evaluacionesActuales.get(caso.getId());

            String decision = obtenerDecision(caso, evaluacion);
            String prioridad = obtenerPrioridad(caso, evaluacion);
            String estado = normalizarCategoria(caso.getCurrentState());
            String semaforo = normalizarCategoria(caso.getAnsSemaphore());

            // Contamos las decisiones actuales del motor, no las decisiones humanas.
            if ("AUTOMATICO".equals(decision)) {
                automaticos++;
            } else if ("MANUAL".equals(decision)) {
                manuales++;
            }

            // Incluimos en el pendiente de pago solo los aprobados no incorporados
            // a un lote; los casos en EN_LOTE_PAGO o CERRADO no se suman aquí.
            if ("APROBADO_AUTOMATICO".equals(estado)
                    || "APROBADO_ANALISTA".equals(estado)) {

                casosPendientesPago++;

                if (caso.getClaimAmount() != null) {
                    valorAPagar = valorAPagar.add(caso.getClaimAmount());
                }
            }

            incrementar(
                    porPrioridad,
                    prioridad == null ? "SIN_PRIORIDAD" : prioridad
            );

            incrementar(porEstado, estado);
            incrementar(
                    porSemaforo,
                    semaforo == null ? "SIN_SEMAFORO" : semaforo
            );
        }

        BigDecimal porcentajeAutomatizacion = total == 0
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(automaticos)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(
                                BigDecimal.valueOf(total),
                                2,
                                RoundingMode.HALF_UP
                        );

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("totalCasos", total);
        respuesta.put("automaticos", automaticos);
        respuesta.put("manuales", manuales);
        respuesta.put(
                "porcentajeAutomatizacion",
                porcentajeAutomatizacion
        );
        respuesta.put("casosPendientesPago", casosPendientesPago);
        respuesta.put("valorAPagar", valorAPagar);
        respuesta.put("casosPorPrioridad", porPrioridad);
        respuesta.put("casosPorEstado", porEstado);
        respuesta.put("casosPorSemaforo", porSemaforo);

        Map<String, Object> bandejaPago = new LinkedHashMap<>();
        bandejaPago.put("cantidad", casosPendientesPago);
        bandejaPago.put("valorTotal", valorAPagar);
        respuesta.put("bandejaPago", bandejaPago);

        long casosEnBandejaManual = todos.stream()
                .filter(caso ->
                        "EN_BANDEJA".equals(caso.getCurrentState())
                                || "EN_REVISION".equals(caso.getCurrentState()))
                .count();

        respuesta.put("casosEnBandejaManual", casosEnBandejaManual);

        return respuesta;
    }

    /**
     * Exporta todas las reclamaciones a CSV UTF-8 compatible con Excel.
     * No incluye nombres, documentos ni otros datos personales del afiliado.
     */
    @Transactional(readOnly = true)
    public byte[] exportarResultadosCsv() {

        List<ClaimCase> todos = new ArrayList<>(casos.findAll());

        todos.sort(
                Comparator.comparing(
                        ClaimCase::getCaseCode,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                )
        );

        Map<Long, CaseEvaluation> evaluacionesActuales =
                cargarEvaluacionesActuales();

        StringBuilder csv = new StringBuilder();

        // El BOM ayuda a Excel a reconocer correctamente los caracteres UTF-8.
        csv.append('\uFEFF');
        agregarFila(csv, COLUMNAS_CSV);

        for (ClaimCase caso : todos) {

            CaseEvaluation evaluacion =
                    evaluacionesActuales.get(caso.getId());

            String decision = obtenerDecision(caso, evaluacion);
            String prioridad = obtenerPrioridad(caso, evaluacion);

            List<String> reglasFallidas = evaluacion == null
                    || evaluacion.getFailedRuleCodes() == null
                    ? List.of()
                    : evaluacion.getFailedRuleCodes();

            String motivo = evaluacion == null
                    ? "Sin evaluación registrada."
                    : evaluacion.getBusinessReason();

            String fechaLimite = caso.getAnsDeadlineDate() == null
                    ? ""
                    : caso.getAnsDeadlineDate().toString();

            String[] fila = {
                    caso.getCaseCode(),
                    decision,
                    String.join("; ", reglasFallidas),
                    motivo,
                    prioridad,
                    caso.getCurrentState(),
                    fechaLimite,
                    caso.getAnsSemaphore()
            };

            agregarFila(csv, fila);
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Obtiene la evaluación vigente más reciente de cada caso. */
    private Map<Long, CaseEvaluation> cargarEvaluacionesActuales() {

        return evaluaciones.findAll()
                .stream()
                .filter(CaseEvaluation::isCurrent)
                .collect(Collectors.toMap(
                        CaseEvaluation::getClaimCaseId,
                        evaluacion -> evaluacion,
                        (primera, segunda) ->
                                segunda.getEvaluationNumber()
                                        >= primera.getEvaluationNumber()
                                        ? segunda
                                        : primera
                ));
    }

    private String obtenerDecision(
            ClaimCase caso,
            CaseEvaluation evaluacion) {

        if (evaluacion != null && evaluacion.getDecision() != null) {
            return evaluacion.getDecision();
        }

        return caso.getCurrentDecision() == null
                ? "SIN_DECISION"
                : caso.getCurrentDecision();
    }

    private String obtenerPrioridad(
            ClaimCase caso,
            CaseEvaluation evaluacion) {

        if (evaluacion != null && evaluacion.getPriority() != null) {
            return evaluacion.getPriority();
        }

        return caso.getCurrentPriority();
    }

    private String normalizarCategoria(String valor) {

        if (valor == null || valor.isBlank()) {
            return null;
        }

        return valor.trim().toUpperCase(Locale.ROOT);
    }

    private void incrementar(
            Map<String, Long> conteo,
            String categoria) {

        conteo.merge(categoria, 1L, Long::sum);
    }

    /**
     * Neutraliza celdas que podrían interpretarse como fórmulas.
     * Se revisa el primer carácter significativo para cubrir espacios previos.
     */
    private String neutralizarFormula(String valor) {

        if (valor == null || valor.isEmpty()) {
            return "";
        }

        int indice = 0;

        while (indice < valor.length()
                && Character.isWhitespace(valor.charAt(indice))) {
            indice++;
        }

        if (indice < valor.length()) {
            char primero = valor.charAt(indice);

            if (primero == '='
                    || primero == '+'
                    || primero == '-'
                    || primero == '@') {

                return "'" + valor;
            }
        }

        return valor;
    }

    /** Encierra cada celda entre comillas y escapa las comillas internas. */
    private void agregarFila(StringBuilder csv, String[] valores) {

        for (int i = 0; i < valores.length; i++) {

            if (i > 0) {
                csv.append(',');
            }

            String seguro = neutralizarFormula(valores[i]);
            String escapado = seguro.replace("\"", "\"\"");

            csv.append('"')
                    .append(escapado)
                    .append('"');
        }

        // Excel reconoce el salto de línea estándar de Windows.
        csv.append("\r\n");
    }
}
