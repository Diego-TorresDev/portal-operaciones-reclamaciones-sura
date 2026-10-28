package com.sura.portal_reclamaciones.dto.importacion;

import java.util.List;

// Resume lo que pasó al importar el archivo, incluyendo las filas que no entraron.
public record ResultadoImportacionExcel(
        boolean exitoso,
        Long loteId,
        String estado,
        int totalFilas,
        int importadas,
        int filasConError,
        String mensaje,
        List<String> errores
) {
}