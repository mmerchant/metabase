import React, { Component, PropTypes } from "react";
import { connect } from "react-redux";
import cx from "classnames";
import MetabaseSettings from "metabase/lib/settings";
import ModalWithTrigger from "metabase/components/ModalWithTrigger.jsx";
import LoadingSpinner from "metabase/components/LoadingSpinner.jsx";

import CreatedDatabaseModal from "../components/CreatedDatabaseModal.jsx";
import DeleteDatabaseModal from "../components/DeleteDatabaseModal.jsx";

import {
    getDatabasesSorted,
    hasSampleDataset
} from "../selectors";
import * as databaseActions from "../database";


const mapStateToProps = (state, props) => {
  return {
      databases:            getDatabasesSorted(state),
      hasSampleDataset:     hasSampleDataset(state),
      engines:              MetabaseSettings.get('engines')
  }
}

const mapDispatchToProps = {
    ...databaseActions
}

@connect(mapStateToProps, mapDispatchToProps)
export default class DatabaseList extends Component {
    static propTypes = {
        databases: PropTypes.array,
        hasSampleDataset: PropTypes.bool,
        engines: PropTypes.object
    };

    componentWillMount() {
        this.props.fetchDatabases();
    }

    render() {
        let { databases, hasSampleDataset, created, engines } = this.props;

        return (
            <div className="wrapper">
                <section className="PageHeader px2 clearfix">
                    <a className="Button Button--primary float-right" href="/admin/databases/create">Add database</a>
                    <h2 className="PageTitle">Databases</h2>
                </section>
                <section>
                    <table className="ContentTable">
                        <thead>
                            <tr>
                                <th>Name</th>
                                <th>Engine</th>
                                <th></th>
                            </tr>
                        </thead>
                        <tbody>
                            { databases ?
                                databases.map(database =>
                                    <tr key={database.id}>
                                        <td>
                                            <a className="text-bold link" href={"/admin/databases/"+database.id}>{database.name}</a>
                                        </td>
                                        <td>
                                            {engines && engines[database.engine] ? engines[database.engine]['driver-name'] : database.engine}
                                        </td>
                                        <td className="Table-actions">
                                            <ModalWithTrigger
                                                ref={"deleteDatabaseModal_"+database.id}
                                                triggerClasses="Button Button--danger"
                                                triggerElement="Delete"
                                            >
                                                <DeleteDatabaseModal
                                                    database={database}
                                                    onClose={() => this.refs["deleteDatabaseModal_"+database.id].close()}
                                                    onDelete={() => this.props.deleteDatabase(database.id)}
                                                />
                                            </ModalWithTrigger>
                                        </td>
                                    </tr>
                                )
                            :
                                <tr>
                                    <td colSpan={4}>
                                        <LoadingSpinner />
                                        <h3>Loading ...</h3>
                                    </td>
                                </tr>
                            }
                        </tbody>
                    </table>
                    { !hasSampleDataset ?
                        <div className="pt4">
                            <span className={cx("p2 text-italic", {"border-top": databases && databases.length > 0})}>
                                <a className="text-grey-2 text-brand-hover no-decoration" href="" onClick={() => this.props.addSampleDataset()}>Bring the sample dataset back</a>
                            </span>
                        </div>
                    : null }
                </section>
                <ModalWithTrigger
                    ref="createdDatabaseModal"
                    isInitiallyOpen={created}
                >
                    <CreatedDatabaseModal
                        onDone={() => this.refs.createdDatabaseModal.toggle() }
                        onClose={() => this.refs.createdDatabaseModal.toggle() }
                    />
                </ModalWithTrigger>
            </div>
        );
    }
}
