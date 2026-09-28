package com.amomeal.marketplace.ingredient.service;

import com.amomeal.marketplace.ingredient.dto.IngredientImportError;
import com.amomeal.marketplace.ingredient.dto.IngredientImportResult;
import com.amomeal.marketplace.ingredient.entity.Ingredient;
import com.amomeal.marketplace.ingredient.entity.IngredientCategory;
import com.amomeal.marketplace.ingredient.exception.IngredientImportFileInvalidException;
import com.amomeal.marketplace.ingredient.exception.IngredientImportFileRequiredException;
import com.amomeal.marketplace.ingredient.repository.IngredientRepository;
import com.amomeal.marketplace.users.entity.CustomUser;
import com.amomeal.marketplace.users.repository.CustomUserRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Mirrors ../../backend/ingredient/services/__init__.py::IngredientImportExportService
 * — openpyxl-based Excel template export / bulk import, ported column-for-
 * column and validation-rule-for-validation-rule using Apache POI.
 */
@Service
@RequiredArgsConstructor
public class IngredientImportExportService {

    /** Mirrors ../../backend/ingredient/constants.py::IMPORT_COLUMNS exactly (order matters). */
    static final List<String> IMPORT_COLUMNS = List.of(
            "name", "category", "weight", "energy", "protein", "lipid", "carbohydrate", "fiber",
            "natri", "kali", "cholesterol", "retinol", "caroten", "vitamin_b_1", "vitamin_b_2",
            "vitamin_pp", "vitamin_c", "calcium", "phosphorus", "fe", "mg", "zn");

    private final IngredientRepository ingredientRepository;
    private final CustomUserRepository customUserRepository;

    public byte[] exportTemplateExcel() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("ingredients");

            String[] allowedCategories = Arrays.stream(IngredientCategory.values()).map(Enum::name).toArray(String[]::new);
            DataValidationHelper helper = new XSSFDataValidationHelper(sheet);
            DataValidationConstraint constraint = helper.createExplicitListConstraint(allowedCategories);
            CellRangeAddressList range = new CellRangeAddressList(1, 999, 1, 1); // B2:B1000
            DataValidation validation = helper.createValidation(constraint, range);
            validation.setShowErrorBox(true);
            sheet.addValidationData(validation);

            Row header = sheet.createRow(0);
            for (int i = 0; i < IMPORT_COLUMNS.size(); i++) {
                header.createCell(i).setCellValue(IMPORT_COLUMNS.get(i));
            }

            Row sample = sheet.createRow(1);
            Object[] sampleValues = {"Ca rot", "VEGETABLE", 100, 41, 0.9, 0.2, 9.6, 2.8,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null};
            for (int i = 0; i < sampleValues.length; i++) {
                Cell cell = sample.createCell(i);
                Object value = sampleValues[i];
                if (value instanceof String s) {
                    cell.setCellValue(s);
                } else if (value instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build ingredient import template", e);
        }
    }

    @Transactional
    public IngredientImportResult importFromExcel(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IngredientImportFileRequiredException();
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IngredientImportFileInvalidException();
        }

        Sheet sheet;
        Workbook workbook;
        try (InputStream in = file.getInputStream()) {
            workbook = WorkbookFactory.create(in);
            sheet = workbook.getSheetAt(0);
        } catch (Exception e) {
            throw new IngredientImportFileInvalidException();
        }

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new IngredientImportFileInvalidException();
        }
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < IMPORT_COLUMNS.size(); i++) {
            Cell cell = headerRow.getCell(i);
            headers.add(cell == null ? "" : String.valueOf(readCellValue(cell)).strip());
        }
        if (!headers.equals(IMPORT_COLUMNS)) {
            throw new IngredientImportFileInvalidException();
        }

        Set<String> existingNames = new HashSet<>();
        for (Ingredient ingredient : ingredientRepository.findAllByDeletedFalse()) {
            existingNames.add(normalizeName(ingredient.getName()));
        }

        CustomUser user = customUserRepository.getReferenceById(userId);

        int totalRows = 0;
        int createdCount = 0;
        int failedCount = 0;
        List<IngredientImportError> errors = new ArrayList<>();

        int lastRow = sheet.getLastRowNum();
        for (int rowIndex = 1; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            int excelRowIndex = rowIndex + 1; // Django: 1-based, header is row 1

            Map<String, Object> rowData = new LinkedHashMap<>();
            for (int col = 0; col < IMPORT_COLUMNS.size(); col++) {
                Cell cell = row == null ? null : row.getCell(col);
                rowData.put(IMPORT_COLUMNS.get(col), cell == null ? null : readCellValue(cell));
            }

            if (rowData.values().stream().allMatch(v -> v == null || "".equals(v))) {
                continue; // skip empty row
            }
            totalRows++;

            rowData.replaceAll((k, v) -> v instanceof String s ? s.strip() : v);

            Object nameValue = rowData.get("name");
            Object categoryValue = rowData.get("category");

            if (nameValue == null || nameValue.toString().isBlank()) {
                failedCount++;
                errors.add(new IngredientImportError(excelRowIndex, "name is required"));
                continue;
            }
            if (categoryValue == null || categoryValue.toString().isBlank()) {
                failedCount++;
                errors.add(new IngredientImportError(excelRowIndex, "category is required"));
                continue;
            }

            String name = nameValue.toString();
            String nameClean = normalizeName(name);

            if (existingNames.contains(nameClean)) {
                failedCount++;
                errors.add(new IngredientImportError(excelRowIndex, "Duplicate ingredient name: " + name));
                continue;
            }
            existingNames.add(nameClean);

            try {
                IngredientCategory category = IngredientCategory.valueOf(categoryValue.toString().strip().toUpperCase(Locale.ROOT));
                Ingredient ingredient = Ingredient.builder()
                        .name(nameClean)
                        .category(category)
                        .weight(asDouble(rowData.get("weight")))
                        .energy(asDouble(rowData.get("energy")))
                        .protein(asDouble(rowData.get("protein")))
                        .lipid(asDouble(rowData.get("lipid")))
                        .carbohydrate(asDouble(rowData.get("carbohydrate")))
                        .fiber(asDouble(rowData.get("fiber")))
                        .natri(asDouble(rowData.get("natri")))
                        .kali(asDouble(rowData.get("kali")))
                        .cholesterol(asDouble(rowData.get("cholesterol")))
                        .retinol(asDouble(rowData.get("retinol")))
                        .caroten(asDouble(rowData.get("caroten")))
                        .vitaminB1(asDouble(rowData.get("vitamin_b_1")))
                        .vitaminB2(asDouble(rowData.get("vitamin_b_2")))
                        .vitaminPp(asDouble(rowData.get("vitamin_pp")))
                        .vitaminC(asDouble(rowData.get("vitamin_c")))
                        .calcium(asDouble(rowData.get("calcium")))
                        .phosphorus(asDouble(rowData.get("phosphorus")))
                        .fe(asDouble(rowData.get("fe")))
                        .mg(asDouble(rowData.get("mg")))
                        .zn(asDouble(rowData.get("zn")))
                        .owner(user)
                        .updater(user)
                        .build();
                ingredientRepository.save(ingredient);
                createdCount++;
            } catch (Exception e) {
                failedCount++;
                errors.add(new IngredientImportError(excelRowIndex, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }

        try {
            workbook.close();
        } catch (IOException ignored) {
            // best-effort cleanup only
        }

        return new IngredientImportResult(totalRows, createdCount, failedCount, errors);
    }

    private static Double asDouble(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(value.toString().strip());
    }

    private static String normalizeName(String name) {
        return String.join(" ", name.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static Object readCellValue(Cell cell) {
        switch (cell.getCellType()) {
            case STRING -> {
                return cell.getStringCellValue();
            }
            case NUMERIC -> {
                return cell.getNumericCellValue();
            }
            case BOOLEAN -> {
                return cell.getBooleanCellValue();
            }
            case FORMULA -> {
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return cell.getNumericCellValue();
                }
            }
            case BLANK -> {
                return null;
            }
            default -> {
                return null;
            }
        }
    }
}
