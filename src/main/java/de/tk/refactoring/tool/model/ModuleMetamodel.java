package de.tk.refactoring.tool.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Complete dependency metamodel of external and internal usages of a target module.
 */
public record ModuleMetamodel(
        String targetModulePackage,
        String implSubPackage,
        List<ApiClassMetadata> apiClasses,
        List<InternCastTarget> internCastTargets,
        List<ConstructorCallTarget> constructorCallTargets
) {
    public String implPackageFqn() {
        if (implSubPackage == null || implSubPackage.isBlank()) {
            return targetModulePackage + ".impl";
        }
        if (implSubPackage.startsWith(targetModulePackage + ".")) {
            return implSubPackage;
        }
        return targetModulePackage + "." + implSubPackage;
    }

    public Map<String, ApiClassMetadata> apiClassesByFqn() {
        Map<String, ApiClassMetadata> map = new LinkedHashMap<>();
        for (ApiClassMetadata cls : apiClasses) {
            map.put(cls.fullyQualifiedName(), cls);
        }
        return map;
    }

    public Map<String, ApiClassMetadata> apiClassesBySimpleName() {
        Map<String, ApiClassMetadata> map = new LinkedHashMap<>();
        for (ApiClassMetadata cls : apiClasses) {
            map.put(cls.simpleName(), cls);
        }
        return map;
    }

    public Optional<ApiClassMetadata> findByFqnOrSimpleName(String name) {
        for (ApiClassMetadata cls : apiClasses) {
            if (cls.fullyQualifiedName().equals(name) || cls.simpleName().equals(name)) {
                return Optional.of(cls);
            }
        }
        return Optional.empty();
    }
}
