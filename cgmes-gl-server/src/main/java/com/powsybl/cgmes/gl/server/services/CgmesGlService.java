/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.cgmes.gl.server.services;

import com.powsybl.cases.datasource.CaseServerDataSource;
import com.powsybl.cgmes.conversion.CgmesImport;
import com.powsybl.cgmes.gl.server.CgmesException;
import com.powsybl.cgmes.gl.server.dto.LineGeoData;
import com.powsybl.cgmes.gl.server.dto.SubstationGeoData;
import com.powsybl.geodata.extensions.LinePosition;
import com.powsybl.geodata.extensions.SubstationPosition;
import com.powsybl.iidm.network.Country;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.impl.NetworkFactoryImpl;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.powsybl.cgmes.gl.server.services.CgmesGlConstants.USERHOME;

/**
 * @author Chamseddine Benhamed <chamseddine.benhamed at rte-france.com>
 */
@Service
public class CgmesGlService {

    private FileSystem fileSystem = FileSystems.getDefault();

    private static final Logger LOGGER = LoggerFactory.getLogger(CgmesGlService.class);

    private RestTemplate geoDataServerRest;
    private String geoDataServerBaseUri;
    private RestTemplate caseServerRest;
    private String caseServerBaseUri;

    private CaseServerDataSource caseServerDataSource;

    @Autowired
    public CgmesGlService(@Value("${geo-data-server.base.url}") String geoDataServerBaseUri, @Value("${case-server.base.url}") String caseServerBaseUri) {
        this.geoDataServerBaseUri = Objects.requireNonNull(geoDataServerBaseUri);
        this.caseServerBaseUri = Objects.requireNonNull(caseServerBaseUri);

        RestTemplateBuilder restTemplateBuilder = new RestTemplateBuilder();
        this.geoDataServerRest = restTemplateBuilder.build();
        this.geoDataServerRest.setUriTemplateHandler(new DefaultUriBuilderFactory(geoDataServerBaseUri));

        RestTemplateBuilder restTemplateBuilder2 = new RestTemplateBuilder();
        this.caseServerRest = restTemplateBuilder2.build();
        this.caseServerRest.setUriTemplateHandler(new DefaultUriBuilderFactory(caseServerBaseUri));

        caseServerDataSource = new CaseServerDataSource(caseServerBaseUri);
    }

    public void toGeodDataServer(String caseName, Set<Country> countries) {
        Network network = getNetwork(caseName);

        List<SubstationPosition> substationPositions = network.getSubstationStream()
                .map(s -> (SubstationPosition) s.getExtension(SubstationPosition.class))
                .filter(Objects::nonNull)
                .filter(s -> countries.isEmpty() || countries.contains(s.getExtendable().getCountry()))
                .collect(Collectors.toList());

        List<LinePosition<Line>> linePositions = new ArrayList<>();
        Country country1;
        Country country2;
        for (Line line : network.getLines()) {
            LinePosition<Line> linePosition = line.getExtension(LinePosition.class);
            country1 = line.getTerminal1().getVoltageLevel().getSubstation().getCountry().orElse(null);
            country2 = line.getTerminal2().getVoltageLevel().getSubstation().getCountry().orElse(null);
            if (linePosition != null && (countries.isEmpty() || countries.contains(country1) || countries.contains(country2))) {
                linePositions.add(linePosition);
            }
        }

        List<LineGeoData> lines = linePositions.stream().map(lp -> LineGeoData.fromLinePosition(lp)).collect(Collectors.toList());
        List<SubstationGeoData> substations = substationPositions.stream().map(sp -> SubstationGeoData.fromSubstationPosition(sp)).collect(Collectors.toList());
        saveData(substations, lines);
    }

    Network getNetwork(String caseName) {
        caseServerDataSource.setCaseName(checkCaseName(caseName));

        CgmesImport importer = new CgmesImport();
        Properties properties = new Properties();
        properties.put("iidm.import.cgmes.post-processors", "cgmesGLImport");
        return importer.importData(caseServerDataSource, new NetworkFactoryImpl(), properties);
    }

    private String checkCaseName(String caseName) {
        //caseName should be a zipped file
        String extension = FilenameUtils.getExtension(caseName);
        if (!extension.equals("zip")) {
            throw new CgmesException("File extension not supported");
        }
        return caseName;
    }

    private void saveData(List<SubstationGeoData> substationsGeoData, List<LineGeoData> linesGeoData) {
        // send geographic data to geo data server
        pushSubstations(substationsGeoData);
        pushLines(linesGeoData);
    }

    private void pushSubstations(List<SubstationGeoData> substationsGeoData) {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(geoDataServerBaseUri + "/" + CgmesGlConstants.GEO_DATA_API_VERSION + "/substations");

        HttpEntity<List<SubstationGeoData>> requestEntity = new HttpEntity<>(substationsGeoData, requestHeaders);

        geoDataServerRest.exchange(uriBuilder.toUriString(),
                HttpMethod.POST,
                requestEntity,
                Void.class);
    }

    private void pushLines(List<LineGeoData> linesGeoData) {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(geoDataServerBaseUri + "/" + CgmesGlConstants.GEO_DATA_API_VERSION + "/lines");

        HttpEntity<List<LineGeoData>> requestEntity = new HttpEntity<>(linesGeoData, requestHeaders);

        geoDataServerRest.exchange(uriBuilder.toUriString(),
                HttpMethod.POST,
                requestEntity,
                Void.class);
    }

    private Path getStorageRootDir() {
        return fileSystem.getPath(System.getProperty(USERHOME) + CgmesGlConstants.TMP_FOLDER);
    }

    private boolean isStorageCreated() {
        Path storageRootDir = getStorageRootDir();
        return Files.exists(storageRootDir) && Files.isDirectory(storageRootDir);
    }

    private void checkStorageInitialization() {
        if (!isStorageCreated()) {
            throw new CgmesException(CgmesGlConstants.STORAGE_DIR_NOT_CREATED);
        }
    }

    void setFileSystem(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    private static void cleanStorage(File storageDir) {
        File[] files = storageDir.listFiles();
        if (files != null) { //some JVMs return null for empty dirs
            for (File f : files) {
                if (f.isDirectory()) {
                    cleanStorage(f);
                } else {
                    f.delete();
                }
            }
        }
        storageDir.delete();
    }
}

