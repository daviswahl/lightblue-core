/*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue.mediator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.lightblue.OperationStatus;
import com.redhat.lightblue.Request;
import com.redhat.lightblue.Response;
import com.redhat.lightblue.crud.BulkRequest;
import com.redhat.lightblue.crud.BulkResponse;
import com.redhat.lightblue.crud.CRUDController;
import com.redhat.lightblue.crud.CRUDDeleteResponse;
import com.redhat.lightblue.crud.CRUDFindResponse;
import com.redhat.lightblue.crud.CRUDOperation;
import com.redhat.lightblue.crud.CRUDUpdateResponse;
import com.redhat.lightblue.crud.ConstraintValidator;
import com.redhat.lightblue.crud.CrudConstants;
import com.redhat.lightblue.crud.DeleteRequest;
import com.redhat.lightblue.crud.DocCtx;
import com.redhat.lightblue.crud.Factory;
import com.redhat.lightblue.crud.FindRequest;
import com.redhat.lightblue.crud.InsertionRequest;
import com.redhat.lightblue.crud.SaveRequest;
import com.redhat.lightblue.crud.UpdateRequest;
import com.redhat.lightblue.crud.WithQuery;
import com.redhat.lightblue.eval.FieldAccessRoleEvaluator;
import com.redhat.lightblue.interceptor.InterceptPoint;
import com.redhat.lightblue.metadata.CompositeMetadata;
import com.redhat.lightblue.metadata.DocId;
import com.redhat.lightblue.metadata.DocIdExtractor;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.Metadata;
import com.redhat.lightblue.metadata.PredefinedFields;
import com.redhat.lightblue.query.BinaryComparisonOperator;
import com.redhat.lightblue.query.FieldInfo;
import com.redhat.lightblue.query.FieldProjection;
import com.redhat.lightblue.query.NaryLogicalExpression;
import com.redhat.lightblue.query.NaryLogicalOperator;
import com.redhat.lightblue.query.Projection;
import com.redhat.lightblue.query.ProjectionList;
import com.redhat.lightblue.query.QueryExpression;
import com.redhat.lightblue.query.Value;
import com.redhat.lightblue.query.ValueComparisonExpression;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.JsonDoc;
import com.redhat.lightblue.util.Path;

/**
 * The mediator looks at a request, performs basic validation, and passes the
 * operation to one or more of the controllers based on the request attributes.
 */
public class Mediator {

    public static final String CTX_QPLAN="meditor:qplan";

    public static final String CRUD_MSG_PREFIX = "CRUD controller={}";

    private static final Logger LOGGER = LoggerFactory.getLogger(Mediator.class);

    private static final Path OBJECT_TYPE_PATH = new Path("objectType");

    private final Metadata metadata;
    private final Factory factory;

    public Mediator(Metadata md,
            Factory factory) {
        this.metadata = md;
        this.factory = factory;
    }

    /**
     * Inserts data
     *
     * @param req Insertion request
     *
     * Mediator performs constraint and role validation, and passes documents
     * that pass the validation to the CRUD implementation for that entity. CRUD
     * implementation can perform further validations.
     */
    public Response insert(InsertionRequest req) {
        LOGGER.debug("insert {}", req.getEntityVersion());
        Error.push("insert(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        try {
            OperationContext ctx = newCtx(req, CRUDOperation.INSERT);
            EntityMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getInsert().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "insert " + ctx.getTopLevelEntityName()));
            } else {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_INSERT, ctx);
                CRUDController controller = factory.getCRUDController(md);
                updatePredefinedFields(ctx,controller,md.getName());
                runBulkConstraintValidation(ctx);
                if (!ctx.hasErrors() && ctx.hasDocumentsWithoutErrors()) {
                    LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());
                    controller.insert(ctx, req.getReturnFields());
                    ctx.getHookManager().queueMediatorHooks(ctx);
                    List<JsonDoc> insertedDocuments = ctx.getOutputDocumentsWithoutErrors();
                    if (insertedDocuments != null && !insertedDocuments.isEmpty()) {
                        response.setEntityData(JsonDoc.listToDoc(insertedDocuments, factory.getNodeFactory()));
                        response.setModifiedCount(insertedDocuments.size());
                    }
                    if (!ctx.hasErrors()&&!ctx.hasDocumentErrors()&&
                        insertedDocuments != null && insertedDocuments.size() == ctx.getDocuments().size()) {
                        ctx.setStatus(OperationStatus.COMPLETE);
                    } else if (insertedDocuments != null && !insertedDocuments.isEmpty()) {
                        ctx.setStatus(OperationStatus.PARTIAL);
                    } else {
                        ctx.setStatus(OperationStatus.ERROR);
                    }
                } else {
                    ctx.setStatus(OperationStatus.ERROR);
                }
                factory.getInterceptors().callInterceptors(InterceptPoint.POST_MEDIATOR_INSERT, ctx);
            }
            response.getDataErrors().addAll(ctx.getDataErrors());
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
            Error.pop();
        }
        return response;
    }

    /**
     * Saves data. Documents in the DB that match the ID of the documents in the
     * request are rewritten. If a document does not exist in the DB and
     * upsert=true, the document is inserted.
     *
     * @param req Save request
     *
     * Mediator performs constraint validation, and passes documents that pass
     * the validation to the CRUD implementation for that entity. CRUD
     * implementation can perform further validations.
     *
     */
    public Response save(SaveRequest req) {
        LOGGER.debug("save {}", req.getEntityVersion());
        Error.push("save(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        try {
            OperationContext ctx = newCtx(req, CRUDOperation.SAVE);
            EntityMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getUpdate().hasAccess(ctx.getCallerRoles())
                    || (req.isUpsert() && !md.getAccess().getInsert().hasAccess(ctx.getCallerRoles()))) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "insert/update " + ctx.getTopLevelEntityName()));
            } else {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_SAVE, ctx);
                CRUDController controller = factory.getCRUDController(md);
                updatePredefinedFields(ctx,controller,md.getName());
                runBulkConstraintValidation(ctx);
                if (!ctx.hasErrors() && ctx.hasDocumentsWithoutErrors()) {
                    LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());
                    controller.save(ctx, req.isUpsert(), req.getReturnFields());
                    ctx.getHookManager().queueMediatorHooks(ctx);
                    List<JsonDoc> updatedDocuments = ctx.getOutputDocumentsWithoutErrors();
                    if (updatedDocuments != null && !updatedDocuments.isEmpty()) {
                        response.setEntityData(JsonDoc.listToDoc(updatedDocuments, factory.getNodeFactory()));
                        response.setModifiedCount(updatedDocuments.size());
                    }
                    if (!ctx.hasErrors()&&!ctx.hasDocumentErrors()&&
                        updatedDocuments != null && updatedDocuments.size() == ctx.getDocuments().size()) {
                        ctx.setStatus(OperationStatus.COMPLETE);
                    } else if (updatedDocuments != null && !updatedDocuments.isEmpty()) {
                        ctx.setStatus(OperationStatus.PARTIAL);
                    } else {
                        ctx.setStatus(OperationStatus.ERROR);
                    }
                }
                factory.getInterceptors().callInterceptors(InterceptPoint.POST_MEDIATOR_SAVE, ctx);
            }
            response.getDataErrors().addAll(ctx.getDataErrors());
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
            Error.pop();
        }
        return response;
    }

    /**
     * Updates documents that match the given search criteria
     *
     * @param req Update request
     *
     * All documents matching the search criteria are updated using the update
     * expression given in the request. Then, the updated document is projected
     * and returned in the response.
     *
     * The mediator does not perform any constraint validation. The CRUD
     * implementation must perform all constraint validations and process only
     * the documents that pass those validations.
     */
    public Response update(UpdateRequest req) {
        LOGGER.debug("update {}", req.getEntityVersion());
        Error.push("update(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        try {
            OperationContext ctx = newCtx(req, CRUDOperation.UPDATE);
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getUpdate().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "update " + ctx.getTopLevelEntityName()));
            } else if(checkQueryAccess(ctx,req.getQuery())) {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_UPDATE, ctx);
                CRUDController controller = factory.getCRUDController(md);
                LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());
                CRUDUpdateResponse updateResponse;
                if(ctx.isSimple()) {
                    updateResponse = controller.update(ctx,
                                                       req.getQuery(),
                                                       req.getUpdateExpression(),
                                                       req.getReturnFields());
                } else {
                    LOGGER.debug("Composite search required for update");
                    QueryExpression q=rewriteUpdateQueryForCompositeSearch(md,ctx);
                    LOGGER.debug("New query:{}",q);
                    if(q!=null)
                        updateResponse=controller.update(ctx,q,req.getUpdateExpression(),req.getReturnFields());
                    else {
                        updateResponse=new CRUDUpdateResponse();
                        updateResponse.setNumUpdated(0);
                        updateResponse.setNumFailed(0);
                    }
                }
                ctx.getHookManager().queueMediatorHooks(ctx);
                LOGGER.debug("# Updated", updateResponse.getNumUpdated());
                response.setModifiedCount(updateResponse.getNumUpdated());
                List<JsonDoc> updatedDocuments = ctx.getOutputDocumentsWithoutErrors();
                if (updatedDocuments != null && !updatedDocuments.isEmpty()) {
                    response.setEntityData(JsonDoc.listToDoc(updatedDocuments, factory.getNodeFactory()));
                }
                if (ctx.hasErrors()) {
                    ctx.setStatus(OperationStatus.ERROR);
                } else if(ctx.hasDocumentErrors()) {
                    ctx.setStatus(OperationStatus.PARTIAL);
                } else {
                    ctx.setStatus(OperationStatus.COMPLETE);
                }
                factory.getInterceptors().callInterceptors(InterceptPoint.POST_MEDIATOR_UPDATE, ctx);
            }
            response.getDataErrors().addAll(ctx.getDataErrors());
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
            Error.pop();
        }
        return response;
    }

    public Response delete(DeleteRequest req) {
        LOGGER.debug("delete {}", req.getEntityVersion());
        Error.push("delete(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        try {
            OperationContext ctx = newCtx(req, CRUDOperation.DELETE);
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getDelete().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "delete " + ctx.getTopLevelEntityName()));
            } else if(checkQueryAccess(ctx,req.getQuery())) {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_DELETE, ctx);
                CRUDController controller = factory.getCRUDController(md);
                LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());

                CRUDDeleteResponse result;
                if(ctx.isSimple()) {
                    result = controller.delete(ctx,req.getQuery());
                } else {
                    LOGGER.debug("Composite search required for delete");
                    QueryExpression q=rewriteUpdateQueryForCompositeSearch(md,ctx);
                    LOGGER.debug("New query:{}",q);
                    if(q!=null)
                        result=controller.delete(ctx,q);
                    else {
                        result=new CRUDDeleteResponse();
                        result.setNumDeleted(0);
                    }
                }
                
                ctx.getHookManager().queueMediatorHooks(ctx);
                response.setModifiedCount(result.getNumDeleted());
                if (ctx.hasErrors()) {
                    ctx.setStatus(OperationStatus.ERROR);
                } else {
                    ctx.setStatus(OperationStatus.COMPLETE);
                }
                factory.getInterceptors().callInterceptors(InterceptPoint.POST_MEDIATOR_DELETE, ctx);
            }
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
            Error.pop();
        }
        return response;
    }

    
    private QueryExpression rewriteUpdateQueryForCompositeSearch(CompositeMetadata md,OperationContext ctx) {
        // Construct a new find request with the composite query
        // Retrieve only the identities
        // This fails if the entity doesn't have identities
        DocIdExtractor docIdx=new DocIdExtractor(md);
        // Identity fields also contains the objectType, we'll filter that out while writing the query
        Path[] identityFields=docIdx.getIdentityFields();

        FindRequest freq=new FindRequest();
        freq.setEntityVersion(ctx.getRequest().getEntityVersion());
        freq.setClientId(ctx.getRequest().getClientId());
        freq.setExecution(ctx.getRequest().getExecution());
        freq.setQuery(((WithQuery)ctx.getRequest()).getQuery());
        // Project the identity fields
        List<Projection> pl=new ArrayList<>(identityFields.length);
        for(Path field:identityFields)
            pl.add(new FieldProjection(field,true,false));
        freq.setProjection(new ProjectionList(pl));
        LOGGER.debug("Query:{} projection:{}",freq.getQuery(),freq.getProjection());
        
        OperationContext findCtx=new OperationContext(freq,CRUDOperation.FIND,ctx);
        Finder finder=new CompositeFindImpl(md,factory);
        CRUDFindResponse response=finder.find(findCtx,freq.getCRUDFindRequest());
        List<JsonDoc> docs=findCtx.getOutputDocumentsWithoutErrors();
        LOGGER.debug("Found documents:{}",docs.size());

        // Now write a query
        List<QueryExpression> orq=new ArrayList<>();
        for(JsonDoc doc:docs) {
            DocId id=docIdx.getDocId(doc);
            List<QueryExpression> idList=new ArrayList<>(identityFields.length);
            for(int ix=0;ix<identityFields.length;ix++) {
                if(!identityFields[ix].equals(PredefinedFields.OBJECTTYPE_PATH)) {
                    Object value=id.getValue(ix);
                    idList.add(new ValueComparisonExpression(identityFields[ix],
                                                             BinaryComparisonOperator._eq,
                                                             new Value(value)));
                }
            }
            QueryExpression idq;
            if(idList.size()==1)
                idq=idList.get(0);
            else
                idq=new NaryLogicalExpression(NaryLogicalOperator._and,idList);
            orq.add(idq);
        }
        if(orq.size()==0)
            return null;
        else if(orq.size()==1)
            return orq.get(0);
        else
            return new NaryLogicalExpression(NaryLogicalOperator._or,orq);
    }
    
    /**
     * Finds documents
     *
     * @param req Find request
     *
     * The implementation passes the request to the back-end.
     */
    public Response find(FindRequest req) {
        LOGGER.debug("find {}", req.getEntityVersion());
        Error.push("find(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        response.setStatus(OperationStatus.ERROR);
        try {
            OperationContext ctx = newCtx(req, CRUDOperation.FIND);
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getFind().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                LOGGER.debug("No access");
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "find " + ctx.getTopLevelEntityName()));
            } else if(checkQueryAccess(ctx,req.getQuery())) {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_FIND, ctx);
                Finder finder;
                if(ctx.isSimple()) {
                    LOGGER.debug("Simple entity");
                    finder=new SimpleFindImpl(md,factory);
                } else {
                    LOGGER.debug("Composite entity");
                    finder=new CompositeFindImpl(md,factory);
                }

                CRUDFindResponse result=finder.find(ctx,req.getCRUDFindRequest());

                List<JsonDoc> foundDocuments = ctx.getOutputDocumentsWithoutErrors();
                if (foundDocuments != null && foundDocuments.size() == ctx.getDocuments().size()) {
                    ctx.setStatus(OperationStatus.COMPLETE);
                } else if (foundDocuments != null && !foundDocuments.isEmpty()) {
                    ctx.setStatus(OperationStatus.PARTIAL);
                } else {
                    ctx.setStatus(OperationStatus.ERROR);
                }

                response.setMatchCount(result.getSize());
                List<DocCtx> documents = ctx.getDocuments();
                if (documents != null) {
                    List<JsonDoc> resultList = new ArrayList<>(documents.size());
                    for (DocCtx doc : documents) {
                        resultList.add(doc.getOutputDocument());
                    }
                    response.setEntityData(JsonDoc.listToDoc(resultList, factory.getNodeFactory()));
                }

                factory.getInterceptors().callInterceptors(InterceptPoint.POST_MEDIATOR_FIND, ctx);
            }
            // call any queued up hooks (regardless of status)
            ctx.getHookManager().queueMediatorHooks(ctx);

            response.setStatus(ctx.getStatus());
            response.getErrors().addAll(ctx.getErrors());
            response.getDataErrors().addAll(ctx.getDataErrors());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            LOGGER.debug("Error during find:{}", e);
            response.getErrors().add(e);
        } catch (Exception e) {
            LOGGER.debug("Exception during find:{}", e);
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
        } finally {
            Error.pop();
        }
        return response;
    }

    public BulkResponse bulkRequest(BulkRequest requests) {
        LOGGER.debug("Bulk request start");
        Error.push("bulk operation");
        BulkResponse responses=new BulkResponse();
        for(Request req:requests.getEntries()) {
            switch(req.getOperation()) {
            case FIND: responses.add(find((FindRequest)req));break;
            case INSERT: responses.add(insert((InsertionRequest)req));break;
            case DELETE: responses.add(delete((DeleteRequest)req));break;
            case UPDATE: responses.add(update((UpdateRequest)req));break;
            case SAVE: responses.add(save((SaveRequest)req));break;
            }
        }
        Error.pop();
        return responses;
    }


    protected OperationContext newCtx(Request request,CRUDOperation CRUDOperation) {
        return new OperationContext(request, metadata, factory, CRUDOperation);
    }

    /**
     * Runs constraint validation
     */
    private void runBulkConstraintValidation(OperationContext ctx) {
        LOGGER.debug("Bulk constraint validation");
        EntityMetadata md = ctx.getTopLevelEntityMetadata();
        ConstraintValidator constraintValidator = factory.getConstraintValidator(md);
        List<DocCtx> docs = ctx.getDocumentsWithoutErrors();
        constraintValidator.validateDocs(docs);
        Map<JsonDoc, List<Error>> docErrors = constraintValidator.getDocErrors();
        for (Map.Entry<JsonDoc, List<Error>> entry : docErrors.entrySet()) {
            JsonDoc doc = entry.getKey();
            List<Error> errors = entry.getValue();
            if (errors != null && !errors.isEmpty()) {
                ((DocCtx) doc).addErrors(errors);
            }
        }
        List<Error> errors = constraintValidator.getErrors();
        if (errors != null && !errors.isEmpty()) {
            ctx.addErrors(errors);
        }
        LOGGER.debug("Constraint validation complete");
    }

    private void updatePredefinedFields(OperationContext ctx,CRUDController controller, String entity) {
        for (JsonDoc doc : ctx.getDocuments()) {
            PredefinedFields.updateArraySizes(ctx.getTopLevelEntityMetadata(),factory.getNodeFactory(), doc);
            JsonNode node = doc.get(OBJECT_TYPE_PATH);
            if (node == null) {
                doc.modify(OBJECT_TYPE_PATH, factory.getNodeFactory().textNode(entity), false);
            } else if (!node.asText().equals(entity)) {
                throw Error.get(CrudConstants.ERR_INVALID_ENTITY, node.asText());
            }
            controller.updatePredefinedFields(ctx,doc);
        }
    }

    /**
     * Checks if the caller has access to all the query fields. Returns false if not, and sets the error status in ctx
     */
    private boolean checkQueryAccess(OperationContext ctx,QueryExpression query) {
        boolean ret=true;
        if(query!=null) {
            CompositeMetadata md=ctx.getTopLevelEntityMetadata();
            FieldAccessRoleEvaluator eval=new FieldAccessRoleEvaluator(md,ctx.getCallerRoles());
            List<FieldInfo> fields=query.getQueryFields();
            LOGGER.debug("Checking access for query fields {}",fields);
            for(FieldInfo field:fields) {
                LOGGER.debug("Access checking field {}",field.getFieldName());
                if(eval.hasAccess(field.getFieldName(),FieldAccessRoleEvaluator.Operation.find)) {
                    LOGGER.debug("Field {} is readable",field.getFieldName());
                } else {
                    LOGGER.debug("Field {} is not readable",field.getFieldName());
                    ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, field.getFieldName().toString()));
                    ctx.setStatus(OperationStatus.ERROR);
                    ret=false;
                }
            }
        }
        return ret;
    }
}
