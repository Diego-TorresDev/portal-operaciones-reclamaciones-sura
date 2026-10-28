package com.sura.portal_reclamaciones.service;

import com.sura.portal_reclamaciones.dto.importacion.ResultadoEvaluacionLote;
import com.sura.portal_reclamaciones.entity.CaseEvaluation;
import com.sura.portal_reclamaciones.entity.CaseEvaluationRule;
import com.sura.portal_reclamaciones.entity.CaseTracking;
import com.sura.portal_reclamaciones.entity.ClaimCase;
import com.sura.portal_reclamaciones.entity.Diagnosis;
import com.sura.portal_reclamaciones.entity.InsurancePlan;
import com.sura.portal_reclamaciones.entity.ParameterConfig;
import com.sura.portal_reclamaciones.entity.Policy;
import com.sura.portal_reclamaciones.entity.Provider;
import com.sura.portal_reclamaciones.entity.RuleDefinition;
import com.sura.portal_reclamaciones.entity.ServiceCatalog;
import com.sura.portal_reclamaciones.repository.CaseEvaluationRepository;
import com.sura.portal_reclamaciones.repository.CaseEvaluationRuleRepository;
import com.sura.portal_reclamaciones.repository.CaseTrackingRepository;
import com.sura.portal_reclamaciones.repository.ClaimCaseRepository;
import com.sura.portal_reclamaciones.repository.DiagnosisRepository;
import com.sura.portal_reclamaciones.repository.InsurancePlanRepository;
import com.sura.portal_reclamaciones.repository.ParameterConfigRepository;
import com.sura.portal_reclamaciones.repository.PolicyRepository;
import com.sura.portal_reclamaciones.repository.ProviderRepository;
import com.sura.portal_reclamaciones.repository.RuleDefinitionRepository;
import com.sura.portal_reclamaciones.repository.ServiceCatalogRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// El motor concentra las reglas para que los controladores no tengan
// que decidir si una reclamación se aprueba o pasa a revisión humana.
@Service
public class MotorReglasService {

    // Mantenemos los códigos en un solo lugar para verificar que el catálogo
    // contenga todas las reglas que exige la prueba técnica.
    private static final List<String> CODIGOS_REGLA = List.of(
            "R01", "R02", "R03", "R04", "R05", "R06", "R07",
            "R08", "R09", "R10", "R11", "R12", "R13", "R14",
            "R15", "R16", "R17"
    );

    // Estas reglas hacen que un caso manual quede en prioridad alta.
    private static final Set<String> REGLAS_PRIORIDAD_ALTA =
            Set.of("R09", "R13", "R16");

    // La prioridad baja solo aplica cuando todos los incumplimientos
    // corresponden a este grupo de reglas.
    private static final Set<String> REGLAS_QUE_PERMITEN_PRIORIDAD_BAJA =
            Set.of("R01", "R02", "R11");

    private final ClaimCaseRepository casos;
    private final RuleDefinitionRepository reglas;
    private final CaseEvaluationRepository evaluaciones;
    private final CaseEvaluationRuleRepository resultadosRegla;
    private final CaseTrackingRepository tracking;
    private final ParameterConfigRepository parametros;
    private final PolicyRepository polizas;
    private final InsurancePlanRepository planes;
    private final ProviderRepository prestadores;
    private final DiagnosisRepository diagnosticos;
    private final ServiceCatalogRepository servicios;
    // Consulta los festivos colombianos y reutiliza la caché de PostgreSQL.
private final FestivosColombiaService festivosColombiaService;

    public MotorReglasService(
            ClaimCaseRepository casos,
            RuleDefinitionRepository reglas,
            CaseEvaluationRepository evaluaciones,
            CaseEvaluationRuleRepository resultadosRegla,
            CaseTrackingRepository tracking,
            ParameterConfigRepository parametros,
            PolicyRepository polizas,
            InsurancePlanRepository planes,
            ProviderRepository prestadores,
            DiagnosisRepository diagnosticos,
            ServiceCatalogRepository servicios,
            FestivosColombiaService festivosColombiaService
    ) {
        this.casos = casos;
        this.reglas = reglas;
        this.evaluaciones = evaluaciones;
        this.resultadosRegla = resultadosRegla;
        this.tracking = tracking;
        this.parametros = parametros;
        this.polizas = polizas;
        this.planes = planes;
        this.prestadores = prestadores;
        this.diagnosticos = diagnosticos;
        this.servicios = servicios;
        // Guardamos la dependencia que calcula los días hábiles con festivos.
        this.festivosColombiaService = festivosColombiaService;
    }

    // Evaluamos los casos recién radicados y permitimos reevaluar los que
    // siguen en bandeja o tienen aprobación automática.
    // Los casos que ya tomó un analista quedan fuera de este proceso.
    @Transactional
    public ResultadoEvaluacionLote evaluarPendientes() {
        List<RuleDefinition> catalogo =
                reglas.findAllByActiveTrueOrderByDisplayOrderAsc();

        Set<String> codigosEncontrados = new LinkedHashSet<>();

        for (RuleDefinition regla : catalogo) {
            codigosEncontrados.add(regla.getRuleCode());
        }

        // No ejecutamos una evaluación parcial si faltan reglas activas.
        if (catalogo.size() != 17
                || !codigosEncontrados.containsAll(CODIGOS_REGLA)) {
            throw new IllegalStateException(
                    "El catálogo debe tener las 17 reglas R01 a R17 activas."
            );
        }

        List<ClaimCase> todos = casos.findAll();

        // R03 utiliza el orden de las filas del archivo para que el primer
        // caso con una combinación de datos no resulte marcado como duplicado.
        todos.sort(Comparator
                .comparing((ClaimCase c) ->
                        c.getImportBatchId() == null
                                ? -1L : c.getImportBatchId())
                .thenComparing(c ->
                        c.getSourceRowNumber() == null
                                ? Integer.MAX_VALUE : c.getSourceRowNumber())
                .thenComparing(c ->
                        c.getId() == null ? 0L : c.getId()));

        Map<Long, Boolean> sonDuplicados = identificarDuplicados(todos);

        List<ClaimCase> elegibles = todos.stream()
                .filter(c -> esEstadoReevaluable(c.getCurrentState()))
                .toList();

        int automaticos = 0;
        int manuales = 0;
        List<String> errores = new ArrayList<>();

        // La operación completa está dentro de una transacción. Si una escritura
        // falla, no dejamos una parte de los casos actualizada y otra sin evaluar.
        for (ClaimCase caso : elegibles) {
            try {
                boolean duplicado =
                        sonDuplicados.getOrDefault(caso.getId(), false);

                evaluarCaso(caso, catalogo, duplicado);

                if ("AUTOMATICO".equals(caso.getCurrentDecision())) {
                    automaticos++;
                } else {
                    manuales++;
                }
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "No se pudo evaluar el caso " + caso.getCaseCode()
                                + ". La operación se revertirá.",
                        error
                );
            }
        }

        return new ResultadoEvaluacionLote(
                todos.size(),
                elegibles.size(),
                automaticos,
                manuales,
                todos.size() - elegibles.size(),
                "Evaluación finalizada. Cada caso procesado fue revisado contra las 17 reglas.",
                errores
        );
    }

    // Las filas posteriores que repiten póliza, NIT, fecha de servicio y monto
    // se consideran posibles duplicados dentro del mismo archivo importado.
    private Map<Long, Boolean> identificarDuplicados(List<ClaimCase> todos) {
        Set<String> clavesVistas = new HashSet<>();
        Map<Long, Boolean> resultado = new HashMap<>();

        for (ClaimCase caso : todos) {
            resultado.put(caso.getId(), false);

            if (caso.getImportBatchId() == null
                    || caso.getNormalizedPolicyNumber() == null
                    || caso.getNormalizedProviderNit() == null
                    || caso.getServiceDate() == null
                    || caso.getClaimAmount() == null) {
                continue;
            }

            String clave = caso.getImportBatchId() + "|"
                    + normalizarCodigo(caso.getNormalizedPolicyNumber()) + "|"
                    + normalizarCodigo(caso.getNormalizedProviderNit()) + "|"
                    + caso.getServiceDate() + "|"
                    + caso.getClaimAmount().stripTrailingZeros().toPlainString();

            // Set.add devuelve false cuando esa combinación ya había aparecido.
            if (!clavesVistas.add(clave)) {
                resultado.put(caso.getId(), true);
            }
        }

        return resultado;
    }

    // No modificamos los casos que ya se encuentran en revisión o terminaron
    // la evaluación humana.
    private boolean esEstadoReevaluable(String estado) {
        return "RADICADO".equals(estado)
                || "APROBADO_AUTOMATICO".equals(estado)
                || "EN_BANDEJA".equals(estado);
    }

    private void evaluarCaso(
            ClaimCase caso,
            List<RuleDefinition> catalogo,
            boolean esDuplicado
    ) {
        Policy poliza = polizas.findByPolicyNumberIgnoreCase(
                valorSeguro(caso.getNormalizedPolicyNumber())
        ).orElse(null);

        Provider prestador = prestadores.findByNit(
                valorSeguro(caso.getNormalizedProviderNit())
        ).orElse(null);

        Diagnosis diagnostico = diagnosticos.findByDiagnosisCodeIgnoreCase(
                valorSeguro(caso.getNormalizedDiagnosisCode())
        ).orElse(null);

        ServiceCatalog servicio = servicios.findByServiceCodeIgnoreCase(
                valorSeguro(caso.getNormalizedServiceType())
        ).orElse(null);

        InsurancePlan plan = poliza == null ? null : poliza.getPlan();

        // La evaluación anterior deja de ser la vigente, pero se conserva
        // para consultar el historial de decisiones y sus parámetros.
        evaluaciones.findByClaimCaseIdAndCurrentTrue(caso.getId())
                .ifPresent(anterior -> {
                    anterior.setCurrent(false);
                    evaluaciones.save(anterior);
                });

        int numeroEvaluacion =
                (int) evaluaciones.countByClaimCaseId(caso.getId()) + 1;

        Map<String, Object> fotoParametros = crearFotoParametros();

        // Creamos la nueva evaluación y la marcamos como vigente.
        CaseEvaluation evaluacion = new CaseEvaluation();
        evaluacion.setClaimCaseId(caso.getId());
        evaluacion.setEvaluationNumber(numeroEvaluacion);
        evaluacion.setCurrent(true);
        evaluacion.setParameterSnapshot(fotoParametros);

        List<String> reglasFallidas = new ArrayList<>();
        List<CaseEvaluationRule> detalleReglas = new ArrayList<>();

        // Revisamos las 17 reglas aunque alguna anterior ya haya fallado.
        for (RuleDefinition regla : catalogo) {
            String codigo = regla.getRuleCode();

            ResultadoRegla resultado = comprobarRegla(
                    codigo,
                    caso,
                    poliza,
                    plan,
                    prestador,
                    diagnostico,
                    servicio,
                    esDuplicado
            );

            CaseEvaluationRule fila = new CaseEvaluationRule();
            fila.setRuleCode(codigo);
            fila.setPassed(resultado.cumple());
            fila.setBusinessExplanation(resultado.explicacion());
            fila.setEvidence(resultado.evidencia());

            detalleReglas.add(fila);

            if (!resultado.cumple()) {
                reglasFallidas.add(codigo);
            }
        }

        boolean automatico = reglasFallidas.isEmpty();
        String decision = automatico ? "AUTOMATICO" : "MANUAL";

        String prioridad = automatico
                ? null
                : calcularPrioridad(caso, reglasFallidas);

        String motivo = automatico
                ? "Cumple las 17 reglas de validación y puede continuar al pago automático."
                : "Requiere revisión manual. Reglas incumplidas: "
                        + String.join(", ", reglasFallidas) + ".";

        evaluacion.setDecision(decision);
        evaluacion.setPriority(prioridad);
        evaluacion.setBusinessReason(motivo);
        evaluacion.setFailedRuleCodes(new ArrayList<>(reglasFallidas));
        evaluacion.setFailedRuleCount((short) reglasFallidas.size());

        evaluacion = evaluaciones.saveAndFlush(evaluacion);

        // Guardamos tanto las reglas cumplidas como las incumplidas.
        for (CaseEvaluationRule fila : detalleReglas) {
            fila.setCaseEvaluationId(evaluacion.getId());
        }

        resultadosRegla.saveAll(detalleReglas);

        String estadoAnterior = caso.getCurrentState();

        // En la primera evaluación registramos la ruta inicial completa.
        if ("RADICADO".equals(estadoAnterior)) {
            registrarCambio(
                    caso,
                    "RADICADO",
                    "VALIDADO",
                    "SISTEMA",
                    "Datos normalizados antes de evaluar las reglas."
            );
            caso.setCurrentState("VALIDADO");

            registrarCambio(
                    caso,
                    "VALIDADO",
                    "EVALUADO",
                    "SISTEMA",
                    "Se aplicaron las 17 reglas de negocio."
            );
            caso.setCurrentState("EVALUADO");

            String destino = automatico
                    ? "APROBADO_AUTOMATICO" : "EN_BANDEJA";

            registrarCambio(
                    caso,
                    "EVALUADO",
                    destino,
                    "SISTEMA",
                    motivo
            );
            caso.setCurrentState(destino);
        } else {
            // En una reevaluación se actualizan las decisiones de los estados
            // permitidos y se conserva el resto del historial.
            String destino = automatico
                    ? "APROBADO_AUTOMATICO" : "EN_BANDEJA";

            if (!destino.equals(estadoAnterior)) {
                registrarCambio(
                        caso,
                        estadoAnterior,
                        destino,
                        "SISTEMA",
                        "Estado actualizado tras reevaluar las reglas."
                );
                caso.setCurrentState(destino);
            }
        }

        caso.setCurrentDecision(decision);
        caso.setCurrentPriority(prioridad);
        caso.setCurrentEvaluationId(evaluacion.getId());

        calcularAnsProvisional(caso, prioridad, automatico);
        casos.save(caso);
    }

    private ResultadoRegla comprobarRegla(
            String codigo,
            ClaimCase caso,
            Policy poliza,
            InsurancePlan plan,
            Provider prestador,
            Diagnosis diagnostico,
            ServiceCatalog servicio,
            boolean duplicado
    ) {
        return switch (codigo) {
            case "R01" -> regla(
                    caso.getDocumentNumber() != null
                            && !caso.getDocumentNumber().isBlank()
                            && caso.getNormalizedPolicyNumber() != null
                            && !caso.getNormalizedPolicyNumber().isBlank()
                            && caso.getClaimAmount() != null
                            && caso.getClaimAmount().compareTo(BigDecimal.ZERO) > 0
                            && caso.getServiceDate() != null
                            && caso.getFilingDate() != null,
                    "Debe tener documento, póliza, valor positivo y ambas fechas válidas.",
                    "Los datos obligatorios están diligenciados."
            );

            case "R02" -> regla(
                    caso.getServiceDate() != null
                            && caso.getFilingDate() != null
                            && !caso.getServiceDate().isAfter(caso.getFilingDate()),
                    "La fecha del servicio es posterior a la fecha de radicación o no se pudo validar.",
                    "La fecha del servicio no es posterior a la radicación."
            );

            case "R03" -> regla(
                    !duplicado,
                    "Ya existe una reclamación anterior con la misma póliza, NIT, fecha de servicio y valor.",
                    "No se encontró una reclamación anterior con los mismos datos."
            );

            case "R04" -> regla(
                    poliza != null,
                    "La póliza indicada no existe en el catálogo de pólizas.",
                    "La póliza existe en el catálogo."
            );

            case "R05" -> regla(
                    poliza != null
                            && "VIGENTE".equals(normalizarCodigo(poliza.getStatus())),
                    "La póliza no está en estado Vigente.",
                    "La póliza está vigente."
            );

            case "R06" -> regla(
                    poliza != null
                            && poliza.getStartDate() != null
                            && poliza.getEndDate() != null
                            && caso.getServiceDate() != null
                            && !caso.getServiceDate().isBefore(poliza.getStartDate())
                            && !caso.getServiceDate().isAfter(poliza.getEndDate()),
                    "La fecha del servicio está por fuera de la vigencia de la póliza.",
                    "La fecha del servicio está dentro de la vigencia de la póliza."
            );

            case "R07" -> {
                int plazo = enteroParametro("PLAZO_RADICACION_DIAS", 90);

                boolean cumple = caso.getServiceDate() != null
                        && caso.getFilingDate() != null;

                if (cumple) {
                    long dias = ChronoUnit.DAYS.between(
                            caso.getServiceDate(),
                            caso.getFilingDate()
                    );

                    cumple = dias >= 0 && dias <= plazo;
                }

                yield regla(
                        cumple,
                        "La reclamación supera el plazo permitido de "
                                + plazo + " días calendario.",
                        "La reclamación fue radicada dentro del plazo permitido."
                );
            }

            case "R08" -> regla(
                    prestador != null && prestador.isInNetwork(),
                    "El NIT del prestador no existe en la red contratada.",
                    "El prestador pertenece a la red contratada."
            );

            case "R09" -> regla(
                    prestador == null || !prestador.isFraudAlert(),
                    "El prestador tiene una alerta de fraude activa.",
                    "El prestador no tiene una alerta de fraude activa."
            );

            case "R10" -> regla(
                    servicio != null && servicio.isActive(),
                    "El tipo de servicio no existe o está inactivo en el catálogo.",
                    "El tipo de servicio existe en el catálogo."
            );

            case "R11" -> {
                List<String> soportesFaltantes = new ArrayList<>();

                if (servicio == null) {
                    soportesFaltantes.add(
                            "No se pudo consultar el catálogo de soportes"
                    );
                } else {
                    List<String> requeridos = servicio.getRequiredSupports();

                    if (requeridos != null) {
                        for (String soporte : requeridos) {
                            Object valor =
                                    caso.getSupportsPayload().get(soporte);

                            if (!esAfirmativo(valor)) {
                                soportesFaltantes.add(nombreSoporte(soporte));
                            }
                        }
                    }
                }

                yield new ResultadoRegla(
                        soportesFaltantes.isEmpty(),
                        soportesFaltantes.isEmpty()
                                ? "Todos los soportes requeridos están adjuntos."
                                : "Faltan soportes obligatorios: "
                                        + String.join(", ", soportesFaltantes) + ".",
                        Map.of("soportesFaltantes", soportesFaltantes)
                );
            }

            case "R12" -> regla(
                    diagnostico != null && !diagnostico.isExcluded(),
                    "El diagnóstico no existe en el catálogo o está excluido.",
                    "El diagnóstico existe y no está excluido."
            );

            case "R13" -> regla(
                    diagnostico == null || !diagnostico.isRequiresMedicalReview(),
                    "El diagnóstico requiere revisión médica.",
                    "El diagnóstico no requiere revisión médica."
            );

            case "R14" -> regla(
                    !normalizarTexto(caso.getNormalizedServiceType())
                            .equals("hospitalizacion"),
                    "El servicio corresponde a Hospitalización, que no admite aprobación automática.",
                    "El servicio no corresponde a Hospitalización."
            );

            // R15 consulta el parámetro vigente del plan en cada evaluación.
            // Así un cambio de ADMIN puede afectar la decisión sin cambiar Java.
            case "R15" -> {
                BigDecimal maximoAutomatico =
                        obtenerMontoMaximoAutomatico(plan);

                yield regla(
                        caso.getClaimAmount() != null
                                && maximoAutomatico != null
                                && caso.getClaimAmount().compareTo(
                                        maximoAutomatico) <= 0,
                        "El valor reclamado supera el monto máximo de aprobación automática del plan o no se pudo validar.",
                        "El valor reclamado está dentro del límite de aprobación automática."
                );
            }

            case "R16" -> {
                boolean cumple = caso.getClaimAmount() != null
                        && poliza != null
                        && poliza.getAnnualConsumedAmount() != null
                        && plan != null
                        && plan.getAnnualCap() != null;

                if (cumple) {
                    BigDecimal totalAnual =
                            poliza.getAnnualConsumedAmount()
                                    .add(caso.getClaimAmount());

                    cumple = totalAnual.compareTo(plan.getAnnualCap()) <= 0;
                }

                yield regla(
                        cumple,
                        "El consumo anual de la póliza más esta reclamación supera el tope anual del plan o no se pudo validar.",
                        "La reclamación no supera el tope anual disponible del plan."
                );
            }

            case "R17" -> {
                List<String> detectadas =
                        palabrasRiesgoDetectadas(caso.getObservations());

                yield new ResultadoRegla(
                        detectadas.isEmpty(),
                        detectadas.isEmpty()
                                ? "No se encontraron indicios de accidente, responsabilidad de terceros o procedimiento estético."
                                : "Las observaciones requieren revisión por estos términos: "
                                        + String.join(", ", detectadas) + ".",
                        Map.of("terminosDetectados", detectadas)
                );
            }

            default -> throw new IllegalArgumentException(
                    "Código de regla no reconocido: " + codigo
            );
        };
    }

    private ResultadoRegla regla(
            boolean cumple,
            String explicacionFallo,
            String explicacionCumplimiento
    ) {
        return new ResultadoRegla(
                cumple,
                cumple ? explicacionCumplimiento : explicacionFallo,
                Map.of()
        );
    }

    // La prioridad alta prevalece si hay reglas críticas o el monto supera
    // el umbral. La prioridad baja solo se usa con fallas exclusivamente leves.
    private String calcularPrioridad(
            ClaimCase caso,
            List<String> fallidas
    ) {
        BigDecimal umbral = decimalParametro(
                "UMBRAL_PRIORIDAD_ALTA",
                new BigDecimal("10000000")
        );

        if (fallidas.stream().anyMatch(REGLAS_PRIORIDAD_ALTA::contains)
                || (caso.getClaimAmount() != null
                && caso.getClaimAmount().compareTo(umbral) > 0)) {
            return "ALTA";
        }

        if (fallidas.stream().allMatch(
                REGLAS_QUE_PERMITEN_PRIORIDAD_BAJA::contains)) {
            return "BAJA";
        }

        return "MEDIA";
    }

    // Obtiene el monto de aprobación automática desde el parámetro propio
    // de cada plan, utilizando el valor del catálogo como respaldo.
    private BigDecimal obtenerMontoMaximoAutomatico(InsurancePlan plan) {
        if (plan == null) {
            return null;
        }

        String codigoPlan = normalizarTexto(plan.getPlanCode())
                .replaceAll("[^a-z0-9]", "");

        String claveParametro = switch (codigoPlan) {
            case "basico" -> "MONTO_AUTO_BASICO";
            case "clasico" -> "MONTO_AUTO_CLASICO";
            case "global" -> "MONTO_AUTO_GLOBAL";
            default -> null;
        };

        // Para un plan desconocido no inventamos un parámetro.
        if (claveParametro == null) {
            return plan.getMaxAutoApprovalAmount();
        }

        String valorConfigurado = textoParametro(claveParametro, "");

        // Si el parámetro está vacío, conservamos el valor del catálogo.
        if (valorConfigurado.isBlank()) {
            return plan.getMaxAutoApprovalAmount();
        }

        BigDecimal montoConfigurado =
                convertirMontoConfigurado(valorConfigurado);

        // Un valor inválido no debe convertirse en un límite inventado.
        return montoConfigurado != null
                && montoConfigurado.signum() >= 0
                ? montoConfigurado
                : plan.getMaxAutoApprovalAmount();
    }

    // Acepta montos sin separadores o con formato colombiano, por ejemplo
    // 2500000, 2.500.000 y $ 2.500.000.
    private BigDecimal convertirMontoConfigurado(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }

        try {
            String limpio = valor.trim()
                    .replaceAll("[^0-9,.-]", "");

            if (limpio.isBlank()) {
                return null;
            }

            // Cuando hay punto y coma en formato colombiano, el punto separa
            // miles y la coma separa decimales: 2.500.000,50.
            if (limpio.contains(",") && limpio.contains(".")) {
                limpio = limpio.replace(".", "").replace(",", ".");
            } else if (limpio.matches("-?\\d{1,3}(\\.\\d{3})+")) {
                // Ejemplo: 2.500.000
                limpio = limpio.replace(".", "");
            } else if (limpio.matches("-?\\d{1,3}(,\\d{3})+")) {
                // También aceptamos grupos de miles separados por comas.
                limpio = limpio.replace(",", "");
            } else if (limpio.contains(",")) {
                // Ejemplo decimal: 2500000,50
                limpio = limpio.replace(",", ".");
            }

            return new BigDecimal(limpio);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    // El ANS sigue siendo provisional: excluye fines de semana, pero aún no
    // consulta la API ni la caché de festivos colombianos.
    // Calcula la fecha límite de ANS respetando los fines de semana,
// los festivos colombianos y la fecha de referencia configurada.
private void calcularAnsProvisional(
        ClaimCase caso,
        String prioridad,
        boolean automatico
) {
    // Cuando el caso ya llegó a uno de los estados que detienen el ANS,
    // el semáforo deja de contar días pendientes.
    if ("EN_LOTE_PAGO".equals(caso.getCurrentState())
            || "OBJETADO".equals(caso.getCurrentState())) {
        caso.setAnsSemaphore("CUMPLIDO");
        caso.setAnsAlert(null);
        return;
    }

    // Si no hay una fecha válida de radicación, no inventamos una fecha límite.
    if (caso.getFilingDate() == null) {
        caso.setAnsDeadlineDate(null);
        caso.setAnsSemaphore(null);
        caso.setAnsAlert(
                "ANS sin validar festivos: falta fecha válida de radicación."
        );
        return;
    }

    // El plazo depende de si el caso es automático o de su prioridad manual.
    int dias = automatico
            ? enteroParametro("ANS_AUTOMATICO_DIAS", 2)
            : switch (prioridad == null ? "MEDIA" : prioridad) {
                case "ALTA" -> enteroParametro("ANS_ALTA_DIAS", 1);
                case "BAJA" -> enteroParametro("ANS_BAJA_DIAS", 5);
                default -> enteroParametro("ANS_MEDIA_DIAS", 3);
            };

    LocalDate fechaLimite = caso.getFilingDate();
    int diasHabilesContados = 0;

    // Guardamos en memoria los resultados por año durante este cálculo.
    // Así no pedimos varias veces el mismo año al servicio de festivos.
    Map<Integer, FestivosColombiaService.ResultadoFestivos> festivosPorAnio =
            new HashMap<>();

    boolean festivosValidados = true;

    // Empezamos a contar desde el día siguiente a la radicación.
    // Sábados, domingos y festivos no consumen días de ANS.
    while (diasHabilesContados < dias) {
        fechaLimite = fechaLimite.plusDays(1);

        DayOfWeek diaSemana = fechaLimite.getDayOfWeek();

        if (diaSemana == DayOfWeek.SATURDAY
                || diaSemana == DayOfWeek.SUNDAY) {
            continue;
        }

        // Si el plazo cruza de año, consultamos también el calendario del año
        // nuevo. El servicio reutiliza la caché si ya se descargó ese calendario.
        int anio = fechaLimite.getYear();

        FestivosColombiaService.ResultadoFestivos calendario =
                festivosPorAnio.computeIfAbsent(
                        anio,
                        festivosColombiaService::obtenerFestivos
                );

        // Si no hay API ni caché disponible para ese año, seguimos calculando
        // sin festivos, pero dejamos una advertencia visible en el caso.
        if (!calendario.validados()) {
            festivosValidados = false;
            diasHabilesContados++;
            continue;
        }

        // Los festivos nacionales tampoco cuentan como días hábiles.
        if (calendario.fechas().contains(fechaLimite)) {
            continue;
        }

        diasHabilesContados++;
    }

    caso.setAnsDeadlineDate(fechaLimite);

    // La alerta solo desaparece cuando todos los años necesarios
    // tuvieron un calendario de festivos validado.
    caso.setAnsAlert(
            festivosValidados ? null : "ANS sin validar festivos"
    );

    LocalDate referencia = fechaReferencia();

    // El día de vencimiento es amarillo; antes es verde y después es rojo.
    if (referencia.isBefore(fechaLimite)) {
        caso.setAnsSemaphore("VERDE");
    } else if (referencia.isEqual(fechaLimite)) {
        caso.setAnsSemaphore("AMARILLO");
    } else {
        caso.setAnsSemaphore("ROJO");
    }
}

    private LocalDate fechaReferencia() {
        String valor = textoParametro("FECHA_REFERENCIA", "");

        if (valor.isBlank()) {
            return LocalDate.now();
        }

        try {
            return LocalDate.parse(valor);
        } catch (Exception ignorado) {
            return LocalDate.now();
        }
    }

    private int enteroParametro(String clave, int valorPorDefecto) {
        try {
            return Integer.parseInt(
                    textoParametro(
                            clave,
                            String.valueOf(valorPorDefecto)
                    ).trim()
            );
        } catch (Exception ignorado) {
            return valorPorDefecto;
        }
    }

    private BigDecimal decimalParametro(
            String clave,
            BigDecimal valorPorDefecto
    ) {
        try {
            String valor = textoParametro(
                    clave,
                    valorPorDefecto.toPlainString()
            ).trim();

            return new BigDecimal(valor);
        } catch (Exception ignorado) {
            return valorPorDefecto;
        }
    }

    private String textoParametro(String clave, String valorPorDefecto) {
        return parametros.findById(clave)
                .map(ParameterConfig::getParameterValue)
                .orElse(valorPorDefecto);
    }

    // Guardamos una copia de los parámetros para explicar por qué se tomó
    // una decisión, incluso si el administrador cambia sus valores después.
    private Map<String, Object> crearFotoParametros() {
        Map<String, Object> foto = new LinkedHashMap<>();

        for (ParameterConfig parametro : parametros.findAll()) {
            foto.put(
                    parametro.getParameterKey(),
                    parametro.getParameterValue()
            );
        }

        return foto;
    }

    // Cada transición se registra como un evento separado de seguimiento.
    private void registrarCambio(
            ClaimCase caso,
            String anterior,
            String nuevo,
            String actor,
            String comentario
    ) {
        CaseTracking evento = new CaseTracking();
        evento.setClaimCaseId(caso.getId());
        evento.setPreviousState(anterior);
        evento.setNewState(nuevo);
        evento.setActorLabel(actor);
        evento.setCommentText(comentario);

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("decisionMotor", true);
        evento.setEventMetadata(datos);

        tracking.save(evento);
    }

    // Aceptamos SI, si, Sí y S como afirmaciones válidas.
    private boolean esAfirmativo(Object valor) {
        if (valor == null) {
            return false;
        }

        String normalizado = normalizarTexto(valor.toString())
                .trim()
                .toUpperCase(Locale.ROOT);

        return "SI".equals(normalizado) || "S".equals(normalizado);
    }

    private String nombreSoporte(String clave) {
        return switch (clave) {
            case "Factura_Adjunta" -> "factura";
            case "Historia_Clinica_Adjunta" -> "historia clínica";
            case "Orden_Medica_Adjunta" -> "orden médica";
            default -> clave;
        };
    }

    private String valorSeguro(String valor) {
        return valor == null ? "" : valor.trim();
    }

    private String normalizarCodigo(String valor) {
        return valor == null
                ? ""
                : valor.trim().toUpperCase(Locale.ROOT);
    }

    // Quitamos tildes para comparar, por ejemplo, "Hospitalización"
    // con "Hospitalizacion".
    private String normalizarTexto(String valor) {
        if (valor == null) {
            return "";
        }

        return Normalizer.normalize(
                valor.trim().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");
    }

    // PALABRAS_R17 se lee de los parámetros para permitir que ADMIN
    // ajuste la lista sin cambiar el código.
    private List<String> palabrasRiesgoConfiguradas() {
        String texto = textoParametro(
                "PALABRAS_R17",
                "choque, tránsito, atropello, moto, andamio, obra, trabajo, "
                        + "laboral, agresión, terceros, rinoplastia, estético, liposucción"
        );

        String limpio = texto
                .replace("[", "")
                .replace("]", "")
                .replace("\"", "");

        List<String> resultado = new ArrayList<>();

        for (String palabra : limpio.split(",")) {
            String normalizada = normalizarTexto(palabra).trim();

            if (!normalizada.isBlank()) {
                resultado.add(normalizada);
            }
        }

        return resultado;
    }

    // Si un término aparece dentro de una negación explícita, no lo marcamos
    // automáticamente como riesgo. Esto ayuda con expresiones como "niega accidentes".
    private List<String> palabrasRiesgoDetectadas(String observaciones) {
        String texto = normalizarTexto(observaciones);
        List<String> detectadas = new ArrayList<>();

        for (String palabra : palabrasRiesgoConfiguradas()) {
            Pattern patron = Pattern.compile(
                    "(?<![a-z0-9])"
                            + Pattern.quote(palabra)
                            + "(?![a-z0-9])"
            );

            Matcher coincidencias = patron.matcher(texto);

            while (coincidencias.find()) {
                int inicio = Math.max(0, coincidencias.start() - 45);

                String contextoPrevio = texto.substring(
                        inicio,
                        coincidencias.start()
                );

                boolean negada = Pattern.compile(
                        "(?:\\bniega(?:n)?\\b|\\bsin\\b|\\bdescarta(?:n)?\\b"
                                + "|\\bno\\s+(?:refiere|presenta|tuvo|reporta|hay)\\b)"
                                + "(?:[a-z0-9\\s]{0,30})$"
                ).matcher(contextoPrevio).find();

                if (!negada) {
                    detectadas.add(palabra);
                    break;
                }
            }
        }

        return detectadas.stream().distinct().toList();
    }

    // Representa la salida de una regla individual. La evaluación guarda
    // tanto el resultado como su explicación y la evidencia asociada.
    private record ResultadoRegla(
            boolean cumple,
            String explicacion,
            Map<String, Object> evidencia
    ) {
    }
}