/*
 * Portions of this software was developed by employees of the National Institute
 * of Standards and Technology (NIST), an agency of the Federal Government and is
 * being made available as a public service. Pursuant to title 17 United States
 * Code Section 105, works of NIST employees are not subject to copyright
 * protection in the United States. This software may be subject to foreign
 * copyright. Permission in the United States and in foreign countries, to the
 * extent that NIST may hold copyright, to use, copy, modify, create derivative
 * works, and distribute this software and its documentation without fee is hereby
 * granted on a non-exclusive basis, provided that this notice and disclaimer
 * of warranty appears in all copies.
 *
 * THE SOFTWARE IS PROVIDED 'AS IS' WITHOUT ANY WARRANTY OF ANY KIND, EITHER
 * EXPRESSED, IMPLIED, OR STATUTORY, INCLUDING, BUT NOT LIMITED TO, ANY WARRANTY
 * THAT THE SOFTWARE WILL CONFORM TO SPECIFICATIONS, ANY IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND FREEDOM FROM
 * INFRINGEMENT, AND ANY WARRANTY THAT THE DOCUMENTATION WILL CONFORM TO THE
 * SOFTWARE, OR ANY WARRANTY THAT THE SOFTWARE WILL BE ERROR FREE.  IN NO EVENT
 * SHALL NIST BE LIABLE FOR ANY DAMAGES, INCLUDING, BUT NOT LIMITED TO, DIRECT,
 * INDIRECT, SPECIAL OR CONSEQUENTIAL DAMAGES, ARISING OUT OF, RESULTING FROM,
 * OR IN ANY WAY CONNECTED WITH THIS SOFTWARE, WHETHER OR NOT BASED UPON WARRANTY,
 * CONTRACT, TORT, OR OTHERWISE, WHETHER OR NOT INJURY WAS SUSTAINED BY PERSONS OR
 * PROPERTY OR OTHERWISE, AND WHETHER OR NOT LOSS WAS SUSTAINED FROM, OR AROSE OUT
 * OF THE RESULTS OF, OR USE OF, THE SOFTWARE OR SERVICES PROVIDED HEREUNDER.
 */

package gov.nist.secauto.oscal.lib.profile.resolver.support;

import gov.nist.secauto.metaschema.model.common.datatype.adapter.UuidAdapter;
import gov.nist.secauto.metaschema.model.common.metapath.MetapathExpression;
import gov.nist.secauto.metaschema.model.common.metapath.MetapathExpression.ResultType;
import gov.nist.secauto.metaschema.model.common.metapath.item.INodeItem;
import gov.nist.secauto.metaschema.model.common.metapath.item.IRequiredValueModelNodeItem;
import gov.nist.secauto.metaschema.model.common.util.CollectionUtil;
import gov.nist.secauto.metaschema.model.common.util.ObjectUtils;
import gov.nist.secauto.oscal.lib.model.BackMatter.Resource;
import gov.nist.secauto.oscal.lib.model.CatalogGroup;
import gov.nist.secauto.oscal.lib.model.Control;
import gov.nist.secauto.oscal.lib.model.ControlPart;
import gov.nist.secauto.oscal.lib.model.Metadata.Location;
import gov.nist.secauto.oscal.lib.model.Parameter;
import gov.nist.secauto.oscal.lib.model.Metadata.Party;
import gov.nist.secauto.oscal.lib.model.Metadata.Role;
import gov.nist.secauto.oscal.lib.profile.resolver.ProfileResolver;
import gov.nist.secauto.oscal.lib.profile.resolver.support.IEntityItem.ItemType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.NonNull;

public class BasicIndexer implements IIndexer {
  private static final Logger LOGGER = LogManager.getLogger(ProfileResolver.class);
  private static final MetapathExpression CONTAINER_METAPATH
      = MetapathExpression.compile("(ancestor::control|ancestor::group)[1])");

  @NonNull
  private final Map<IEntityItem.ItemType, Map<String, IEntityItem>> entityTypeToIdentifierToEntityMap;
  @NonNull
  private Map<INodeItem, SelectionStatus> nodeItemToSelectionStatusMap;

  @Override
  public void append(@NonNull IIndexer other) {
    for (ItemType itemType : ItemType.values()) {
      for (IEntityItem entity : other.getEntitiesByItemType(itemType)) {
        addItem(entity);
      }
    }

    this.nodeItemToSelectionStatusMap.putAll(other.getSelectionStatusMap());
  }

  public BasicIndexer() {
    this.entityTypeToIdentifierToEntityMap = new EnumMap<>(IEntityItem.ItemType.class);
    this.nodeItemToSelectionStatusMap = new ConcurrentHashMap<>();
  }

  public BasicIndexer(IIndexer other) {
    Map<IEntityItem.ItemType, Map<String, IEntityItem>> entities = other.getEntities();

    // copy entity map
    this.entityTypeToIdentifierToEntityMap = new ConcurrentHashMap<>();
    for (ItemType itemType : ItemType.values()) {
      Map<String, IEntityItem> entityGroup = entities.get(itemType);
      if (entityGroup != null) {
        entityTypeToIdentifierToEntityMap.put(itemType, new LinkedHashMap<>(entityGroup));
      }
    }

    // copy selection map
    this.nodeItemToSelectionStatusMap = new ConcurrentHashMap<>(other.getSelectionStatusMap());
  }

  @Override
  public SelectionStatus setSelectionStatus(@NonNull INodeItem item, @NonNull SelectionStatus selectionStatus) {
    SelectionStatus retval = nodeItemToSelectionStatusMap.put(item, selectionStatus);
    return retval == null ? SelectionStatus.UNKNOWN : retval;
  }

  @Override
  public Map<INodeItem, SelectionStatus> getSelectionStatusMap() {
    return CollectionUtil.unmodifiableMap(nodeItemToSelectionStatusMap);
  }

  @Override
  public SelectionStatus getSelectionStatus(@NonNull INodeItem item) {
    SelectionStatus retval = nodeItemToSelectionStatusMap.get(item);
    return retval == null ? SelectionStatus.UNKNOWN : retval;
  }

  @Override
  public void resetSelectionStatus() {
    nodeItemToSelectionStatusMap = new ConcurrentHashMap<>();
  }

  @Override
  public boolean isSelected(@NonNull IEntityItem entity) {
    boolean retval;
    switch (entity.getItemType()) {
    case CONTROL:
    case GROUP:
      retval = IIndexer.SelectionStatus.SELECTED.equals(getSelectionStatus(entity.getInstance()));
      break;
    case PART: {
      IRequiredValueModelNodeItem instance = entity.getInstance();
      IIndexer.SelectionStatus status = getSelectionStatus(instance);
      if (IIndexer.SelectionStatus.UNKNOWN.equals(status)) {
        // lookup the status if not known
        IRequiredValueModelNodeItem containerItem = CONTAINER_METAPATH.evaluateAs(instance, ResultType.NODE);
        status = getSelectionStatus(containerItem);

        // cache the status
        setSelectionStatus(instance, status);
      }
      retval = IIndexer.SelectionStatus.SELECTED.equals(status);
      break;
    }
    case PARAMETER:
    case LOCATION:
    case PARTY:
    case RESOURCE:
    case ROLE:
      // always "selected"
      retval = true;
      break;
    default:
      throw new UnsupportedOperationException(entity.getItemType().name());
    }
    return retval;
  }

  @Override
  public Map<ItemType, Map<String, IEntityItem>> getEntities() {
    return entityTypeToIdentifierToEntityMap.entrySet().stream()
        .map(entry -> Map.entry(entry.getKey(), CollectionUtil.unmodifiableMap(entry.getValue())))
        .collect(Collectors.toUnmodifiableMap(entry -> entry.getKey(), entry -> entry.getValue()));
  }

  @Override
  @NonNull
  // TODO: rename to getEntitiesForItemType
  public Collection<IEntityItem> getEntitiesByItemType(@NonNull IEntityItem.ItemType itemType) {
    Map<String, IEntityItem> entityGroup = entityTypeToIdentifierToEntityMap.get(itemType);
    return entityGroup == null ? CollectionUtil.emptyList() : ObjectUtils.notNull(entityGroup.values());
  }
  //
  // public EntityItem getEntity(@NonNull ItemType itemType, @NonNull UUID identifier) {
  // return getEntity(itemType, ObjectUtils.notNull(identifier.toString()), false);
  // }
  //
  // public EntityItem getEntity(@NonNull ItemType itemType, @NonNull String identifier) {
  // return getEntity(itemType, identifier, itemType.isUuid());
  // }

  @Override
  public IEntityItem getEntity(@NonNull ItemType itemType, @NonNull String identifier, boolean normalize) {
    Map<String, IEntityItem> entityGroup = entityTypeToIdentifierToEntityMap.get(itemType);
    String normalizedIdentifier = normalize ? normalizeIdentifier(identifier) : identifier;
    return entityGroup == null ? null : entityGroup.get(normalizedIdentifier);
  }

  protected IEntityItem addItem(@NonNull IEntityItem item) {
    IEntityItem.ItemType type = item.getItemType();

    Map<String, IEntityItem> entityGroup = entityTypeToIdentifierToEntityMap.get(type);
    if (entityGroup == null) {
      entityGroup = new LinkedHashMap<>();
      entityTypeToIdentifierToEntityMap.put(type, entityGroup);
    }
    IEntityItem oldEntity = entityGroup.put(item.getIdentifier(), item);
    if (oldEntity != null) {
      if (LOGGER.isWarnEnabled()) {
        LOGGER.atWarn().log("Duplicate {} found with identifier {} in index.",
            oldEntity.getItemType().name().toLowerCase(Locale.ROOT),
            oldEntity.getIdentifier());
      }
    }
    return oldEntity;
  }

  @NonNull
  protected IEntityItem addItem(@NonNull AbstractEntityItem.Builder builder) {
    IEntityItem retval = builder.build();
    addItem(retval);
    return retval;
  }

  @Override
  public boolean remove(@NonNull IEntityItem entity) {
    IEntityItem.ItemType type = entity.getItemType();
    Map<String, IEntityItem> entityGroup = entityTypeToIdentifierToEntityMap.get(type);

    boolean retval = false;
    if (entityGroup != null) {
      retval = entityGroup.remove(entity.getIdentifier(), entity);

      // remove if present
      nodeItemToSelectionStatusMap.remove(entity.getInstance());

      if (retval) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.atDebug().log("Removing {} '{}' from index.", type.name(), entity.getIdentifier());
        }
      } else if (LOGGER.isDebugEnabled()) {
        LOGGER.atDebug().log("The {} entity '{}' was not found in the index to remove.",
            type.name(),
            entity.getIdentifier());
      }
    }
    return retval;
  }

  @Override
  public IEntityItem addRole(IRequiredValueModelNodeItem item) {
    Role role = (Role) item.getValue();
    String identifier = ObjectUtils.requireNonNull(role.getId());

    return addItem(newBuilder(item, ItemType.ROLE, identifier));
  }

  @Override
  public IEntityItem addLocation(IRequiredValueModelNodeItem item) {
    Location location = (Location) item.getValue();
    UUID identifier = ObjectUtils.requireNonNull(location.getUuid());

    return addItem(newBuilder(item, ItemType.LOCATION, identifier));
  }

  @Override
  public IEntityItem addParty(IRequiredValueModelNodeItem item) {
    Party party = (Party) item.getValue();
    UUID identifier = ObjectUtils.requireNonNull(party.getUuid());

    return addItem(newBuilder(item, ItemType.PARTY, identifier));
  }

  @Override
  public IEntityItem addGroup(IRequiredValueModelNodeItem item) {
    CatalogGroup group = (CatalogGroup) item.getValue();
    String identifier = group.getId();
    return identifier == null ? null : addItem(newBuilder(item, ItemType.GROUP, identifier));
  }

  @Override
  public IEntityItem addControl(IRequiredValueModelNodeItem item) {
    Control control = (Control) item.getValue();
    String identifier = ObjectUtils.requireNonNull(control.getId());
    return addItem(newBuilder(item, ItemType.CONTROL, identifier));
  }

  @Override
  public IEntityItem addParameter(IRequiredValueModelNodeItem item) {
    Parameter parameter = (Parameter) item.getValue();
    String identifier = ObjectUtils.requireNonNull(parameter.getId());

    return addItem(newBuilder(item, ItemType.PARAMETER, identifier));
  }

  @Override
  public IEntityItem addPart(IRequiredValueModelNodeItem item) {
    ControlPart part = (ControlPart) item.getValue();
    String identifier = part.getId();

    return identifier == null ? null : addItem(newBuilder(item, ItemType.PART, identifier));
  }

  @Override
  public IEntityItem addResource(IRequiredValueModelNodeItem item) {
    Resource resource = (Resource) item.getValue();
    UUID identifier = ObjectUtils.requireNonNull(resource.getUuid());

    return addItem(newBuilder(item, ItemType.RESOURCE, identifier));
  }

  @NonNull
  protected final AbstractEntityItem.Builder newBuilder(
      @NonNull IRequiredValueModelNodeItem item,
      @NonNull ItemType itemType,
      @NonNull UUID identifier) {
    return newBuilder(item, itemType, ObjectUtils.notNull(identifier.toString()));
  }

  /**
   * Create a new builder with the provided info.
   * <p>
   * This method can be overloaded to support applying additional data to the returned builder.
   * <p>
   * When working with identifiers that are case insensitve, it is important to ensure that the
   * identifiers are normalized to lower case.
   * 
   * @param item
   *          the Metapath node to associate with the entity
   * @param itemType
   *          the type of entity
   * @param identifier
   *          the entity's identifier
   * @return the entity builder
   */
  @NonNull
  protected AbstractEntityItem.Builder newBuilder(
      @NonNull IRequiredValueModelNodeItem item,
      @NonNull ItemType itemType,
      @NonNull String identifier) {
    return new AbstractEntityItem.Builder()
        .instance(item, itemType)
        .originalIdentifier(identifier)
        .source(ObjectUtils.requireNonNull(item.getBaseUri(), "item must have an associated URI"));
  }

  /**
   * Lower case UUID-based identifiers and leave others unmodified.
   * 
   * @param identifier
   *          the identifier
   * @return the resulting normalized identifier
   */
  @NonNull
  public String normalizeIdentifier(@NonNull String identifier) {
    return UuidAdapter.UUID_PATTERN.matches(identifier) ? ObjectUtils.notNull(identifier.toLowerCase(Locale.ROOT))
        : identifier;
  }
  //
  // private static class ItemGroup {
  // @NonNull
  // private final ItemType itemType;
  // Map<String, IEntityItem> idToEntityMap;
  //
  // public ItemGroup(@NonNull ItemType itemType) {
  // this.itemType = itemType;
  // this.idToEntityMap = new LinkedHashMap<>();
  // }
  //
  // public IEntityItem getEntity(@NonNull String identifier) {
  // return idToEntityMap.get(identifier);
  // }
  //
  // @SuppressWarnings("null")
  // @NonNull
  // public Collection<IEntityItem> getEntities() {
  // return idToEntityMap.values();
  // }
  //
  // public IEntityItem add(@NonNull IEntityItem entity) {
  // assert itemType.equals(entity.getItemType());
  // return idToEntityMap.put(entity.getOriginalIdentifier(), entity);
  // }
  // }
}
