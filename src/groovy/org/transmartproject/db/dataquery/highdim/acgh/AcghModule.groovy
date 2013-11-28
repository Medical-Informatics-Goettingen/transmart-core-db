package org.transmartproject.db.dataquery.highdim.acgh

import grails.orm.HibernateCriteriaBuilder
import org.hibernate.ScrollableResults
import org.hibernate.engine.SessionImplementor
import org.springframework.beans.factory.annotation.Autowired
import org.transmartproject.core.dataquery.TabularResult
import org.transmartproject.core.dataquery.highdim.AssayColumn
import org.transmartproject.core.dataquery.highdim.HighDimensionDataTypeResource
import org.transmartproject.core.dataquery.highdim.projections.Projection
import org.transmartproject.core.exceptions.InvalidArgumentsException
import org.transmartproject.core.exceptions.UnexpectedResultException
import org.transmartproject.db.dataquery.highdim.AbstractHighDimensionDataTypeModule
import org.transmartproject.db.dataquery.highdim.DefaultHighDimensionTabularResult
import org.transmartproject.db.dataquery.highdim.parameterproducers.DataRetrievalParameterFactory
import org.transmartproject.db.dataquery.highdim.parameterproducers.MapBasedParameterFactory

import static org.hibernate.sql.JoinFragment.INNER_JOIN

class AcghModule extends AbstractHighDimensionDataTypeModule {

    static final String ACGH_VALUES_PROJECTION = 'acgh_values'

    final String name = 'acgh'

    @Autowired
    DataRetrievalParameterFactory standardAssayConstraintFactory

    @Autowired
    DataRetrievalParameterFactory standardDataConstraintFactory

    @Autowired
    DataRetrievalParameterFactory acghDataConstraintProducers

    @Override
    HighDimensionDataTypeResource createHighDimensionResource(Map params) {
        /* return instead subclass of HighDimensionDataTypeResourceImpl,
         * because we add a method, retrieveChromosomalSegments() */
        new AcghDataTypeResource(this)
    }

    @Override
    protected List<DataRetrievalParameterFactory> createAssayConstraintFactories() {
        [ standardAssayConstraintFactory ]
    }

    @Override
    protected List<DataRetrievalParameterFactory> createDataConstraintFactories() {
        [
                standardDataConstraintFactory,
                acghDataConstraintProducers
        ]
    }

    @Override
    protected List<DataRetrievalParameterFactory> createProjectionFactories() {
        [
                new MapBasedParameterFactory(
                        (ACGH_VALUES_PROJECTION): { Map<String, Object> params ->
                            if (!params.isEmpty()) {
                                throw new InvalidArgumentsException('Expected no parameters here')
                            }
                            new AcghValuesProjection()
                        }
                )
        ]
    }

    @Override
    HibernateCriteriaBuilder prepareDataQuery(Projection projection, SessionImplementor session) {
        HibernateCriteriaBuilder criteriaBuilder =
            createCriteriaBuilder(DeSubjectAcghData, 'acgh', session)

        criteriaBuilder.with {
            createAlias 'jRegion', 'region', INNER_JOIN

            projections {
                property 'acgh.assay.id'
                property 'acgh.chipCopyNumberValue'
                property 'acgh.segmentCopyNumberValue'
                property 'acgh.flag'
                property 'acgh.probabilityOfLoss'
                property 'acgh.probabilityOfNormal'
                property 'acgh.probabilityOfGain'
                property 'acgh.probabilityOfAmplification'

                property 'region.id'
                property 'region.cytoband'
                property 'region.chromosome'
                property 'region.start'
                property 'region.end'
                property 'region.numberOfProbes'
            }

            order 'region.id', 'asc'
            order 'assay.id',  'asc' // important
        }

        criteriaBuilder
    }

    @Override
    TabularResult transformResults(ScrollableResults results,
                                   List<AssayColumn> assays,
                                   Projection projection) {
        /* assumption here is the assays in the passed in list are in the same
         * order as the assays in the result set */
        Map assayIndexMap = createAssayIndexMap assays

        new DefaultHighDimensionTabularResult(
                rowsDimensionLabel:    'Regions',
                columnsDimensionLabel: 'Sample codes',
                indicesList:           assays,
                results:               results,
                inSameGroup:           { a, b -> a[8] == b[8] /* region.id */ },
                finalizeGroup:         { List list -> /* list of arrays with 14 elements (1/projection) */
                    if (list.size() != assays.size()) {
                        throw new UnexpectedResultException(
                                "Expected group to be of size ${assays.size()}; got ${list.size()} objects")
                    }
                    def regionRow = new RegionRow(Arrays.asList(list[0]))
                    regionRow.assayIndexMap = assayIndexMap
                    regionRow.data = list.collect {
                        projection.doWithResult(Arrays.asList(it))
                    }
                    regionRow
                }
        )
    }
}