/* @flow weak */

import _ from "underscore";
import { updateIn, setIn } from "icepick";

import { createSelector } from 'reselect';

import * as Dashboard from "metabase/meta/Dashboard";
import Metadata from "metabase/meta/metadata/Metadata";

import Query from "metabase/lib/query";

import type { CardObject } from "metabase/meta/types/Card";
import type { ParameterMappingOption, ParameterObject } from "metabase/meta/types/Dashboard";

export const getSelectedDashboard = state => state.router.params.dashboardId;
export const getIsEditing         = state => state.dashboard.isEditing;
export const getCards             = state => state.dashboard.cards;
export const getDashboards        = state => state.dashboard.dashboards;
export const getDashcards         = state => state.dashboard.dashcards;
export const getCardData          = state => state.dashboard.dashcardData;
export const getCardDurations     = state => state.dashboard.cardDurations;
export const getCardIdList        = state => state.dashboard.cardList;
export const getRevisions         = state => state.dashboard.revisions;
export const getParameterValues   = state => state.dashboard.parameterValues;

export const getDatabases         = state => state.metadata.databases;

export const getMetadata = createSelector(
    [getDatabases],
    (databases) =>
        new Metadata(Object.entries(databases).map(([k,v]) => v)) // not sure why flow doesn't like Object.values() here
)

export const getDashboard = createSelector(
    [getSelectedDashboard, getDashboards],
    (selectedDashboard, dashboards) => dashboards[selectedDashboard]
);

export const getDashboardComplete = createSelector(
    [getDashboard, getDashcards],
    (dashboard, dashcards) => (dashboard && {
        ...dashboard,
        ordered_cards: dashboard.ordered_cards.map(id => dashcards[id]).filter(dc => !dc.isRemoved)
    })
);

export const getIsDirty = createSelector(
    [getDashboard, getDashcards],
    (dashboard, dashcards) => !!(
        dashboard && (
            dashboard.isDirty ||
            _.some(dashboard.ordered_cards, id => (
                !(dashcards[id].isAdded && dashcards[id].isRemoved) &&
                (dashcards[id].isDirty || dashcards[id].isAdded || dashcards[id].isRemoved)
            ))
        )
    )
);

export const getCardList = createSelector(
    [getCardIdList, getCards],
    (cardIdList, cards) => cardIdList && cardIdList.map(id => cards[id])
);

export const getEditingParameterId = (state) => state.dashboard.editingParameterId;

export const getEditingParameter = createSelector(
    [getDashboard, getEditingParameterId],
    (dashboard, editingParameterId) => editingParameterId != null ? _.findWhere(dashboard.parameters, { id: editingParameterId }) : null
);

export const getIsEditingParameter = (state) => state.dashboard.editingParameterId != null;

const getCard = (state, props) => props.card;
const getDashCard = (state, props) => props.dashcard;

export const getParameterTarget = createSelector(
    [getEditingParameter, getCard, getDashCard],
    (parameter, card, dashcard) => {
        const mapping = _.findWhere(dashcard.parameter_mappings, { card_id: card.id, parameter_id: parameter.id });
        return mapping && mapping.target;
    }
);

export const getMappingsByParameter = createSelector(
    [getMetadata, getDashboardComplete],
    (metadata, dashboard) => {
        let mappingsByParameter = {};
        let countsByParameter = {};
        let mappings = [];
        for (const dashcard of dashboard.ordered_cards) {
            for (let mapping of (dashcard.parameter_mappings || [])) {
                let values = null;
                if (mapping.target[0] === "dimension") {
                    let dimension = mapping.target[1];
                    let field = metadata.field(Query.getFieldTargetId(dimension));
                    values = field && field.values() ;
                    if (values) {
                        for (const value of values) {
                            countsByParameter = updateIn(countsByParameter, [mapping.parameter_id, value], (count = 0) => count + 1)
                        }
                    }
                }
                mapping = {
                    ...mapping,
                    parameter_id: mapping.parameter_id,
                    dashcard_id: dashcard.id,
                    card_id: mapping.card_id,
                    values
                };
                mappingsByParameter = setIn(mappingsByParameter, [mapping.parameter_id, dashcard.id, mapping.card_id], mapping);
                mappings.push(mapping);
            }
        }
        let mappingsWithValuesByParameter = {};
        // update max values overlap for each mapping
        for (let mapping of mappings) {
            if (mapping.values && mapping.values.length > 0) {
                let overlapMax = Math.max(...mapping.values.map(value => countsByParameter[mapping.parameter_id][value]))
                mappingsByParameter = setIn(mappingsByParameter, [mapping.parameter_id, mapping.dashcard_id, mapping.card_id, "overlapMax"], overlapMax);
                mappingsWithValuesByParameter = updateIn(mappingsWithValuesByParameter, [mapping.parameter_id], (count = 0) => count + 1);
            }
        }
        // update count of mappings with values
        for (let mapping of mappings) {
            mappingsByParameter = setIn(mappingsByParameter, [mapping.parameter_id, mapping.dashcard_id, mapping.card_id, "mappingsWithValues"], mappingsWithValuesByParameter[mapping.parameter_id] || 0);
        }

        return mappingsByParameter;
    }
);

export const makeGetParameterMappingOptions = () => {
    const getParameterMappingOptions = createSelector(
        [getMetadata, getEditingParameter, getCard],
        (metadata, parameter: ParameterObject, card: CardObject): Array<ParameterMappingOption> => {
            return Dashboard.getParameterMappingOptions(metadata, parameter, card);
        }
    );
    return getParameterMappingOptions;
}
